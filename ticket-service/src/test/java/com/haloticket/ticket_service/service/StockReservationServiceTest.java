package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.entity.TicketStockReservation;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.StockReservationStatus;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.repository.TicketStockReservationRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against an in-memory database with real transactions (no wrapping test transaction), so the conditional
 * UPDATE and the row locks behave the way concurrent bookings would see them.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
@Import(StockReservationService.class)
class StockReservationServiceTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    private static final String EVENT = "event-1";

    @Autowired StockReservationService service;
    @Autowired TicketTypeRepository types;
    @Autowired TicketStockReservationRepository reservations;

    @AfterEach
    void clean() {
        reservations.deleteAll();
        types.deleteAll();
    }

    private TicketType type(String name, int total) {
        return types.save(TicketType.builder().eventId(EVENT).name(name).price(100000d)
                .totalQuantity(total).initialQuantity(total).build());
    }

    private Instant later() {
        return Instant.now().plusSeconds(900);
    }

    private TicketType reload(TicketType t) {
        return types.findById(t.getId()).orElseThrow();
    }

    private int reserved(TicketType t) {
        Integer value = reload(t).getReservedQuantity();
        return value == null ? 0 : value;
    }

    @Test
    void twentyConcurrentBookingsForFiveLastTicketsGiveExactlyFiveSuccesses() throws Exception {
        TicketType ga = type("GA", 5);
        int buyers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch ready = new CountDownLatch(buyers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger soldOut = new AtomicInteger();
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String bookingId = "booking-" + i;
            Callable<Boolean> task = () -> {
                ready.countDown();
                go.await();
                try {
                    service.reserve(bookingId, EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 1)));
                    return true;
                } catch (StockReservationService.SoldOutException e) {
                    soldOut.incrementAndGet();
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(5);
        assertThat(soldOut.get()).isEqualTo(15);
        assertThat(reserved(ga)).isEqualTo(5);
        assertThat(reload(ga).getTotalQuantity()).isEqualTo(5);
        assertThat(reservations.findAll()).hasSize(5)
                .allMatch(r -> r.getStatus() == StockReservationStatus.HELD);
    }

    @Test
    void twoBuyersOfTheSameTypeBothSucceedWhileStockLasts() throws Exception {
        TicketType ga = type("GA", 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> service.reserve("a", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 3))));
        Future<?> b = pool.submit(() -> service.reserve("b", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 4))));
        a.get();
        b.get();
        pool.shutdown();

        assertThat(reserved(ga)).isEqualTo(7);
    }

    @Test
    void largerOrderIsRefusedWithoutHoldingAnythingWhenItDoesNotFit() {
        TicketType ga = type("GA", 5);
        assertThatThrownBy(() -> service.reserve("big", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 6))))
                .isInstanceOfSatisfying(StockReservationService.SoldOutException.class, e -> assertThat(e.getAvailable()).isEqualTo(5));
        assertThat(reserved(ga)).isZero();
        assertThat(reservations.findAll()).isEmpty();
    }

    @Test
    void multiItemOrderIsAllOrNothing() {
        TicketType plenty = type("GA", 10);
        TicketType scarce = type("VIP", 1);
        assertThatThrownBy(() -> service.reserve("mixed", EVENT, null, later(), List.of(
                new StockReservationService.Item(plenty.getId(), 2),
                new StockReservationService.Item(scarce.getId(), 2))))
                .isInstanceOf(StockReservationService.SoldOutException.class);

        assertThat(reserved(plenty)).isZero();
        assertThat(reserved(scarce)).isZero();
        assertThat(reservations.findByBookingId("mixed")).isEmpty();
    }

    @Test
    void reservingTheSameBookingTwiceHoldsOnce() {
        TicketType ga = type("GA", 10);
        List<StockReservationService.Item> items = List.of(new StockReservationService.Item(ga.getId(), 2));
        service.reserve("same", EVENT, null, later(), items);
        service.reserve("same", EVENT, null, later(), items);

        assertThat(reserved(ga)).isEqualTo(2);
        assertThat(reservations.findByBookingId("same")).hasSize(1);
    }

    @Test
    void releaseGivesStockBackAndIsIdempotent() {
        TicketType ga = type("GA", 10);
        service.reserve("r", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 4)));

        assertThat(service.release("r")).isEqualTo(1);
        assertThat(service.release("r")).isZero();

        assertThat(reserved(ga)).isZero();
        assertThat(reservations.findByBookingId("r")).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(StockReservationStatus.RELEASED));
    }

    @Test
    void releasedBookingCannotBeReservedAgainUnderTheSameId() {
        TicketType ga = type("GA", 10);
        List<StockReservationService.Item> items = List.of(new StockReservationService.Item(ga.getId(), 1));
        service.reserve("closed", EVENT, null, later(), items);
        service.release("closed");

        assertThatThrownBy(() -> service.reserve("closed", EVENT, null, later(), items))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("RESERVATION_CLOSED"));
        assertThat(reserved(ga)).isZero();
    }

    @Test
    void expiredHoldsAreReleasedByTheSweep() {
        TicketType ga = type("GA", 10);
        service.reserve("fresh", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 2)));
        TicketType held = reload(ga);
        held.setReservedQuantity(held.getReservedQuantity() + 3);
        types.save(held);
        reservations.save(TicketStockReservation.builder().bookingId("stale").ticketTypeId(ga.getId()).quantity(3)
                .status(StockReservationStatus.HELD).expiresAt(Instant.now().minusSeconds(5)).build());
        assertThat(reserved(ga)).isEqualTo(5);

        assertThat(service.expireDue(100)).isEqualTo(1);

        assertThat(reserved(ga)).isEqualTo(2);
        assertThat(reservations.findByBookingId("stale")).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(StockReservationStatus.EXPIRED));
        assertThat(reservations.findByBookingId("fresh")).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(StockReservationStatus.HELD));
    }

    @Test
    void consumingAtIssuanceTurnsTheHoldIntoNothingHeldAndLeavesTotalToTheDecrement() {
        TicketType ga = type("GA", 10);
        service.reserve("paid", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 2)));

        assertThat(service.consumeForBooking("paid")).isEqualTo(1);
        assertThat(service.consumeForBooking("paid")).isZero();

        assertThat(reserved(ga)).isZero();
        assertThat(reload(ga).getTotalQuantity()).isEqualTo(10);
        assertThat(reservations.findByBookingId("paid")).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(StockReservationStatus.CONSUMED));
    }

    @Test
    void expiredHoldCannotBeConsumedSoIssuanceKeepsItsOldBehaviour() {
        TicketType ga = type("GA", 10);
        reservations.save(TicketStockReservation.builder().bookingId("late").ticketTypeId(ga.getId()).quantity(2)
                .status(StockReservationStatus.EXPIRED).expiresAt(Instant.now().minusSeconds(60)).build());

        assertThat(service.consumeForBooking("late")).isZero();
        assertThat(reserved(ga)).isZero();
    }

    @Test
    void hiddenTypeCannotBeReserved() {
        TicketType hidden = types.save(TicketType.builder().eventId(EVENT).name("Hidden").price(1d)
                .totalQuantity(10).initialQuantity(10).ticketTypeStatus(TicketTypeStatus.HIDDEN).build());

        assertThatThrownBy(() -> service.reserve("h", EVENT, null, later(), List.of(new StockReservationService.Item(hidden.getId(), 1))))
                .isInstanceOf(StockReservationService.SoldOutException.class);
    }

    @Test
    void typeFromAnotherEventIsRejected() {
        TicketType other = types.save(TicketType.builder().eventId("other-event").name("GA").price(1d)
                .totalQuantity(10).initialQuantity(10).build());

        assertThatThrownBy(() -> service.reserve("x", EVENT, null, later(), List.of(new StockReservationService.Item(other.getId(), 1))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("TICKET_TYPE_EVENT_MISMATCH"));
    }

    @Test
    void invalidRequestsAreRejected() {
        TicketType ga = type("GA", 10);
        assertThatThrownBy(() -> service.reserve("", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 1))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reserve("b", EVENT, null, later(), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reserve("b", EVENT, null, Instant.now().minusSeconds(1), List.of(new StockReservationService.Item(ga.getId(), 1))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reserve("b", EVENT, null, later(), List.of(new StockReservationService.Item(ga.getId(), 0))))
                .isInstanceOf(ResponseStatusException.class);
    }
}
