package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.repository.*;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.haloticket.ticket_service.service.StockReservationService;

class TicketEventListenerTest {
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TicketTypeRepository types = mock(TicketTypeRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final SeatLayoutRepository layouts = mock(SeatLayoutRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TicketEventListener listener = new TicketEventListener(
            tickets, outbox, types, seats, layouts, entityManager, mapper,
            mock(StockReservationService.class));
    private final List<Ticket> saved = new ArrayList<>();

    private void setup() {
        TicketType oldPro = TicketType.builder().id("old-pro").eventId("event").showtimeId("old-show")
                .name("PRO").price(15000.0).totalQuantity(10).build();
        TicketType pro = TicketType.builder().id("pro").eventId("event").showtimeId("show")
                .name("PRO").price(15000.0).totalQuantity(10).build();
        TicketType vip = TicketType.builder().id("vip").eventId("event").showtimeId("show")
                .name("VIP").price(12000.0).totalQuantity(10).build();
        when(types.findById("old-pro")).thenReturn(Optional.of(oldPro));
        when(types.findById("pro")).thenReturn(Optional.of(pro));
        when(types.findByShowtimeId("show")).thenReturn(List.of(vip, pro));
        when(layouts.findFirstByEventIdAndStatusOrderByUpdatedAtDesc("event", SeatLayoutStatus.PUBLISHED)).thenReturn(Optional.of(
                SeatLayout.builder().draftJson("""
                        {"seats":[{"id":"seat_rh_24","zoneId":"rh_vip","row":"B","number":2,
                        "tierId":"old-pro"}],"zones":{"rh_vip":{"name":"TẦNG 1","tierId":"old-pro"}}}
                        """).build()));
        when(seats.findById("seat_rh_24")).thenReturn(Optional.empty());
        when(types.decrementTotalQuantityAtomic(anyString())).thenReturn(1);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("bookingId"), eq("booking"))).thenReturn(lockQuery);
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
    void syntheticSeatResolvesShowtimeTypeAndIssuesOnlyPurchasedQuantity() throws Exception {
        setup();
        String confirmed = """
                {"bookingId":"booking","bookingCode":"B001","customerId":"buyer",
                "customerEmail":"buyer@example.com","customerName":"Buyer","eventId":"event",
                "showtimeId":"show","amount":15000,"seatIds":["seat_rh_24"],
                "items":[{"ticketTypeId":"pro","quantity":1,"unitPrice":15000}]}
                """;
        listener.handleBookingConfirmed(confirmed);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getTicketType().getId()).isEqualTo("pro");
        assertThat(saved.getFirst().getSeatReference()).isEqualTo("seat_rh_24");
        assertThat(saved.getFirst().getSeatRowLabel()).isEqualTo("B");
        assertThat(saved.getFirst().getSeatNumberLabel()).isEqualTo("2");
        var event = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        JsonNode payload = mapper.readTree(event.getValue().getPayload());
        assertThat(payload.path("tickets")).hasSize(1);
        assertThat(payload.path("tickets").get(0).path("ticketTypeName").asText()).isEqualTo("PRO");
        assertThat(payload.path("tickets").get(0).path("row").asText()).isEqualTo("B");
        assertThat(payload.path("bookingCode").asText()).isEqualTo("B001");

        listener.handleBookingConfirmed(confirmed);
        assertThat(saved).hasSize(1);
        verify(outbox, times(1)).save(any());
    }

    @Test
    void seatTypeMismatchNeverIssuesExtraTicketOrEvent() {
        setup();
        String confirmed = """
                {"bookingId":"booking","customerId":"buyer","eventId":"event","showtimeId":"show",
                "seatIds":["seat_rh_24"],"items":[{"ticketTypeId":"vip","quantity":1}]}
                """;
        when(types.findById("vip")).thenReturn(Optional.of(
                TicketType.builder().id("vip").eventId("event").showtimeId("show").name("VIP").price(12000.0).build()));
        assertThatThrownBy(() -> listener.handleBookingConfirmed(confirmed))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        verify(tickets, never()).save(any());
        verify(outbox, never()).save(any());
    }
}
