package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.enums.StockReservationStatus;
import com.haloticket.ticket_service.repository.*;
import com.haloticket.ticket_service.service.StockReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issuance (the only path that creates purchased tickets) decrements total_quantity exactly once per new ticket, in the
 * same real transaction that creates the ticket; an exhausted type rolls everything back. Real H2 transactions.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
@Import(StockReservationService.class)
class TicketIssuanceStockDecrementTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    private static final String EVENT = "event-1";
    private static final String SHOW = "show-1";

    @Autowired TicketRepository tickets;
    @Autowired OutboxEventRepository outbox;
    @Autowired TicketTypeRepository types;
    @Autowired SeatRepository seats;
    @Autowired SeatRowRepository seatRows;
    @Autowired SeatZoneRepository seatZones;
    @Autowired SeatLayoutRepository layouts;
    @Autowired TicketStockReservationRepository reservations;
    @Autowired StockReservationService stockReservations;
    @Autowired PlatformTransactionManager txManager;

    private TicketEventListener listener;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        EntityManager em = mock(EntityManager.class); // only used for the postgres advisory lock
        Query lock = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        when(lock.setParameter(eq("bookingId"), any())).thenReturn(lock);
        when(lock.getSingleResult()).thenReturn(1);
        listener = new TicketEventListener(tickets, outbox, types, seats, layouts, em, new ObjectMapper(), stockReservations);
        tx = new TransactionTemplate(txManager);
    }

    @AfterEach
    void clean() {
        tickets.deleteAll();
        outbox.deleteAll();
        seats.deleteAll();
        seatRows.deleteAll();
        seatZones.deleteAll();
        layouts.deleteAll();
        reservations.deleteAll();
        types.deleteAll();
    }

    private TicketType type(String name, int stock) {
        return types.save(TicketType.builder().eventId(EVENT).showtimeId(SHOW).name(name).price(100000d)
                .totalQuantity(stock).initialQuantity(stock).build());
    }

    private TicketType reload(TicketType t) {
        return types.findById(t.getId()).orElseThrow();
    }

    private void deliver(String booking) {
        tx.executeWithoutResult(status -> listener.handleBookingConfirmed(booking));
    }

    private static String booking(String id, String seatIds, String items) {
        return """
                {"bookingId":"%s","bookingCode":"C-%s","customerId":"buyer","eventId":"%s","showtimeId":"%s",
                 "seatIds":[%s],"items":[%s]}
                """.formatted(id, id, EVENT, SHOW, seatIds, items);
    }

    private static String item(TicketType t, int qty) {
        return "{\"ticketTypeId\":\"%s\",\"quantity\":%d,\"unitPrice\":100000}".formatted(t.getId(), qty);
    }

    @Test
    void withoutAHoldStockDropsOncePerTicket() {
        TicketType ga = type("GA", 10);
        deliver(booking("b1", "", item(ga, 2)));

        assertThat(reload(ga).getTotalQuantity()).isEqualTo(8);
        assertThat(tickets.findByBookingId("b1")).hasSize(2);
    }

    @Test
    void withAHoldTheHoldIsConsumedAndStockStillDropsOncePerTicket() {
        TicketType ga = type("GA", 10);
        stockReservations.reserve("b1", EVENT, SHOW, Instant.now().plusSeconds(600), List.of(new StockReservationService.Item(ga.getId(), 2)));
        assertThat(reload(ga).getReservedQuantity()).isEqualTo(2);

        deliver(booking("b1", "", item(ga, 2)));

        TicketType after = reload(ga);
        assertThat(after.getTotalQuantity()).isEqualTo(8);
        assertThat(after.getReservedQuantity()).isZero();
        assertThat(reservations.findByBookingId("b1")).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(StockReservationStatus.CONSUMED));
    }

    @Test
    void redeliveryDoesNotDecrementAgain() {
        TicketType ga = type("GA", 10);
        String message = booking("b1", "", item(ga, 3));

        deliver(message);
        deliver(message);

        assertThat(reload(ga).getTotalQuantity()).isEqualTo(7);
        assertThat(tickets.findByBookingId("b1")).hasSize(3);
    }

    @Test
    void threeGaTicketsOfOneTypeDecrementByExactlyThree() {
        TicketType ga = type("GA", 10);
        String seats3 = "\"%1$s-STT-1\",\"%1$s-STT-2\",\"%1$s-STT-3\"".formatted(ga.getId());

        deliver(booking("b1", seats3, item(ga, 3)));

        assertThat(reload(ga).getTotalQuantity()).isEqualTo(7);
        assertThat(tickets.findByBookingId("b1")).hasSize(3);
    }

    @Test
    void twoTypesEachDecrementTheirOwnStock() {
        TicketType vip = type("VIP", 10);
        TicketType std = type("STD", 10);

        deliver(booking("b1", "", item(vip, 1) + "," + item(std, 2)));

        assertThat(reload(vip).getTotalQuantity()).isEqualTo(9);
        assertThat(reload(std).getTotalQuantity()).isEqualTo(8);
        assertThat(tickets.findByBookingId("b1")).hasSize(3);
    }

    @Test
    void seatMapSeatAndGaTicketsInOneOrderDecrementOncePerTicket() {
        TicketType vip = type("VIP", 10);
        TicketType std = type("STD", 10);
        SeatLayout layout = layouts.save(SeatLayout.builder().eventId(EVENT).status(SeatLayoutStatus.PUBLISHED).build());
        SeatZone zone = seatZones.save(SeatZone.builder().seatLayout(layout).name("VIP ZONE").ticketTypeId(vip.getId()).build());
        SeatRow row = seatRows.save(SeatRow.builder().seatZone(zone).label("A").build());
        Seat seat = seats.save(Seat.builder().seatRow(row).seatNumber("1").build());

        // 2 VIP (one real seat + one standing) and 1 STD standing
        deliver(booking("b1", "\"" + seat.getId() + "\"", item(vip, 2) + "," + item(std, 1)));

        assertThat(reload(vip).getTotalQuantity()).isEqualTo(8);
        assertThat(reload(std).getTotalQuantity()).isEqualTo(9);
        assertThat(tickets.findByBookingId("b1")).hasSize(3);
        assertThat(seats.findById(seat.getId()).orElseThrow().getSeatStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void exhaustedStockThrowsOversoldAndRollsBackTicketsAndPartialDecrement() {
        TicketType ga = type("GA", 1);

        assertThatThrownBy(() -> deliver(booking("b1", "", item(ga, 2))))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("OVERSOLD_ON_ISSUE");

        assertThat(reload(ga).getTotalQuantity()).isEqualTo(1); // the first decrement was rolled back too
        assertThat(tickets.findByBookingId("b1")).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void concurrentBookingsForTheLastTicketsIssueExactlyTheStockAndNeverGoNegative() throws Exception {
        TicketType ga = type("GA", 5);
        int buyers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger oversold = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String message = booking("c" + i, "", item(ga, 1));
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    deliver(message);
                    ok.incrementAndGet();
                } catch (RuntimeException e) {
                    if (String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()).contains("OVERSOLD_ON_ISSUE")
                            || rootMessage(e).contains("OVERSOLD_ON_ISSUE")) {
                        oversold.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(5);
        assertThat(oversold.get()).isEqualTo(7);
        assertThat(reload(ga).getTotalQuantity()).isZero();
        assertThat(tickets.findAll()).hasSize(5);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
