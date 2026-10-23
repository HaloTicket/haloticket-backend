package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.repository.*;
import com.haloticket.ticket_service.service.StockReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Held general-admission stock is consumed in the issuance transaction; without a hold nothing else changes. */
class TicketIssuanceStockReservationTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TicketTypeRepository types = mock(TicketTypeRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final StockReservationService stockReservations = mock(StockReservationService.class);
    private final List<Ticket> saved = new ArrayList<>();
    private TicketEventListener listener;

    private static final String GA_ORDER = """
            {"bookingId":"booking","bookingCode":"B1","customerId":"buyer","eventId":"event","showtimeId":"show",
            "seatIds":[],"items":[{"ticketTypeId":"ga","quantity":2,"unitPrice":50000}]}
            """;

    @BeforeEach
    void setUp() {
        listener = new TicketEventListener(tickets, outbox, types, mock(SeatRepository.class),
                mock(SeatLayoutRepository.class), entityManager, new ObjectMapper(), stockReservations);
        TicketType ga = TicketType.builder().id("ga").eventId("event").showtimeId("show").name("GA").price(50000.0)
                .totalQuantity(10).build();
        when(types.findById("ga")).thenReturn(Optional.of(ga));
        when(types.findByShowtimeId("show")).thenReturn(List.of(ga));
        when(types.decrementTotalQuantityAtomic("ga")).thenReturn(1);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("bookingId"), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);
        when(tickets.findByBookingId("booking")).thenAnswer(invocation -> List.copyOf(saved));
        when(tickets.save(any())).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId("ticket-" + (saved.size() + 1));
            saved.add(ticket);
            return ticket;
        });
    }

    @Test
    void gaOrderWithoutSeatIdsIssuesFromItemsAndConsumesTheHoldBeforeDecrementing() {
        listener.handleBookingConfirmed(GA_ORDER);

        assertThat(saved).hasSize(2).allMatch(t -> t.getSeat() == null && t.getSeatReference() == null);
        InOrder order = inOrder(stockReservations, types);
        order.verify(stockReservations).consumeForBooking("booking");
        order.verify(types, times(2)).decrementTotalQuantityAtomic("ga");
    }

    @Test
    void withoutAHoldIssuanceStillRunsExactlyAsBefore() {
        when(stockReservations.consumeForBooking("booking")).thenReturn(0);

        listener.handleBookingConfirmed(GA_ORDER);

        assertThat(saved).hasSize(2);
        verify(types, times(2)).decrementTotalQuantityAtomic("ga");
        verify(outbox, times(1)).save(any());
    }

    @Test
    void redeliveredConfirmationDoesNotIssueTwice() {
        listener.handleBookingConfirmed(GA_ORDER);
        listener.handleBookingConfirmed(GA_ORDER);

        assertThat(saved).hasSize(2);
        verify(outbox, times(1)).save(any());
    }
}
