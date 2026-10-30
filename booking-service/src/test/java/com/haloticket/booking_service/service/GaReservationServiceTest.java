package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GaReservationServiceTest {

    private static final String TOKEN = "tok";
    private static final String GA = "11111111-1111-1111-1111-111111111111";
    private static final String VIP = "22222222-2222-2222-2222-222222222222";

    private GaReservationProperties properties;
    private TicketStockClient client;
    private GaReservationService service;

    @BeforeEach
    void setUp() {
        properties = new GaReservationProperties();
        properties.setInternalToken(TOKEN);
        client = mock(TicketStockClient.class);
        service = new GaReservationService(properties, client, new ObjectMapper());
    }

    private static BookingItemRequest item(String typeId, int quantity, Boolean generalAdmission) {
        BookingItemRequest item = new BookingItemRequest();
        item.setTicketTypeId(typeId);
        item.setQuantity(quantity);
        item.setUnitPrice(100000d);
        item.setGeneralAdmission(generalAdmission);
        return item;
    }

    private static BookingCreateRequest request(List<String> seatIds, BookingItemRequest... items) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setEventId("event-1");
        request.setShowtimeId("show-1");
        request.setSeatIds(seatIds);
        request.setItems(List.of(items));
        return request;
    }

    // --- plan -----------------------------------------------------------------



    @Test
    void orderWithoutSeatIdsIsAllGeneralAdmission() {
        var plan = service.plan(request(List.of(), item(GA, 3, null)));

        assertThat(plan.gaItems()).containsExactly(new GaReservationService.GaItem(GA, 3));
        assertThat(plan.hasSeats()).isFalse();
    }

    @Test
    void nullSeatIdsAreTreatedLikeNoSeats() {
        var plan = service.plan(request(null, item(GA, 1, null)));
        assertThat(plan.gaItems()).containsExactly(new GaReservationService.GaItem(GA, 1));
    }


    @Test
    void realSeatsAreLeftAloneWhenNothingIsFlagged() {
        var plan = service.plan(request(List.of("seat_1_2", "seat_1_3"), item(VIP, 2, null)));

        assertThat(plan.hasGa()).isFalse();
        assertThat(plan.seatIds()).containsExactly("seat_1_2", "seat_1_3");
    }

    @Test
    void mixedOrderSplitsFlaggedGaFromRealSeats() {
        var plan = service.plan(request(List.of("seat_1_2", "seat_1_3"), item(VIP, 2, null), item(GA, 3, true)));

        assertThat(plan.gaItems()).containsExactly(new GaReservationService.GaItem(GA, 3));
        assertThat(plan.seatIds()).containsExactly("seat_1_2", "seat_1_3");
    }



    @Test
    void invalidGaQuantityIsRejected() {
        assertThatThrownBy(() -> service.plan(request(List.of(), item(GA, 0, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- reserve / release ------------------------------------------------------

    private static FeignException.Conflict conflict(String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ticket-service/x", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return new FeignException.Conflict("conflict", request, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private GaReservationService.Plan gaPlan(int quantity) {
        return new GaReservationService.Plan(List.of(new GaReservationService.GaItem(GA, quantity)), List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserveSendsTheTokenAndAHoldThatOutlivesTheBookingBySlack() {
        properties.setSlackSeconds(60);
        service.reserve("booking-1", request(List.of(), item(GA, 2, null)), gaPlan(2), LocalDateTime.now().plusMinutes(15));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).reserve(eq(TOKEN), body.capture());
        assertThat(body.getValue()).containsEntry("bookingId", "booking-1").containsEntry("eventId", "event-1")
                .containsEntry("showtimeId", "show-1");
        assertThat(body.getValue().get("items")).isEqualTo(List.of(Map.of("ticketTypeId", GA, "quantity", 2)));
        assertThat(java.time.Instant.parse((String) body.getValue().get("expiresAt")))
                .isAfter(java.time.Instant.now().plusSeconds(15 * 60));
    }

    @Test
    void soldOutBecomesConflictTicketSoldOut() {
        doThrow(conflict("{\"code\":\"TICKET_SOLD_OUT\",\"available\":1}")).when(client).reserve(anyString(), any());

        assertThatThrownBy(() -> service.reserve("b", request(List.of(), item(GA, 2, null)), gaPlan(2), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("TICKET_SOLD_OUT");
                });
    }

    @Test
    void otherConflictIsNotReportedAsSoldOut() {
        doThrow(conflict("{\"code\":\"RESERVATION_CLOSED\"}")).when(client).reserve(anyString(), any());

        assertThatThrownBy(() -> service.reserve("b", request(List.of(), item(GA, 1, null)), gaPlan(1), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("TICKET_RESERVATION_REFUSED"));
    }

    @Test
    void ticketServiceFailureFailsClosedWith503() {
        doThrow(new RuntimeException("connection refused")).when(client).reserve(anyString(), any());

        assertThatThrownBy(() -> service.reserve("b", request(List.of(), item(GA, 1, null)), gaPlan(1), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("TICKET_STOCK_UNAVAILABLE");
                });
    }

    @Test
    void blankTokenFailsClosedWithoutCallingTicketService() {
        properties.setInternalToken("");

        assertThatThrownBy(() -> service.reserve("b", request(List.of(), item(GA, 1, null)), gaPlan(1), LocalDateTime.now().plusMinutes(15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(client);
    }

    @Test
    void releaseNeverThrowsEvenWhenTicketServiceIsDown() {
        doThrow(new RuntimeException("down")).when(client).release(anyString(), anyString());

        service.releaseQuietly("booking-1");

        verify(client).release(TOKEN, "booking-1");
    }

    @Test
    void releaseIfReservedOnlyActsOnBookingsThatHeldStock() {
        var held = com.haloticket.booking_service.entity.Booking.builder().id("held").gaReserved(true).build();
        var plain = com.haloticket.booking_service.entity.Booking.builder().id("plain").build();
        var explicitFalse = com.haloticket.booking_service.entity.Booking.builder().id("false").gaReserved(false).build();

        service.releaseIfReserved(held);
        service.releaseIfReserved(plain);
        service.releaseIfReserved(explicitFalse);
        service.releaseIfReserved(null);

        verify(client).release(TOKEN, "held");
        verifyNoMoreInteractions(client);
    }

}
