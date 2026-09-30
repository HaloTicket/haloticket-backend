package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketMarketplaceControllerTest {

    @Test
    void resaleEligibilityIncludesSafeTicketDisplaySnapshot() throws Exception {
        TicketRepository tickets = mock(TicketRepository.class);
        TicketService ticketService = mock(TicketService.class);
        TicketMarketplaceController controller = new TicketMarketplaceController(tickets, ticketService);
        SeatZone zone = SeatZone.builder().name("Khán đài A").build();
        SeatRow row = SeatRow.builder().label("A1").seatZone(zone).build();
        Seat seat = Seat.builder().seatNumber("20").seatRow(row).build();
        Ticket ticket = Ticket.builder().id("ticket-1").eventId("event-1").ownerId("seller-1")
                .ticketType(TicketType.builder().id("type-1").showtimeId("show-1").name("VIP").price(120000D).build())
                .seat(seat).originalPrice(new BigDecimal("120000")).status(TicketStatus.VALID).build();
        when(tickets.findById("ticket-1")).thenReturn(Optional.of(ticket));
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), Map.of("sub", "seller-1"));

        Object response = controller.resaleEligibility("ticket-1", jwt);

        assertThat(value(response, "ticketTypeName")).isEqualTo("VIP");
        assertThat(value(response, "section")).isEqualTo("Khán đài A");
        assertThat(value(response, "row")).isEqualTo("A1");
        assertThat(value(response, "seat")).isEqualTo("20");
        assertThat(value(response, "ticketTypeId")).isEqualTo("type-1");
        assertThat(value(response, "showtimeId")).isEqualTo("show-1");
    }

    private static Object value(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
