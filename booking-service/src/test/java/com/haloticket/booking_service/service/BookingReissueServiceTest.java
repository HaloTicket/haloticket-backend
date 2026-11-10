package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingReissueServiceTest {

    private BookingRepository bookings;
    private OutboxEventRepository outbox;
    private TicketStockClient tickets;
    private KafkaTemplate<String, String> kafka;
    private BookingReissueService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        outbox = mock(OutboxEventRepository.class);
        tickets = mock(TicketStockClient.class);
        kafka = mock(KafkaTemplate.class);
        GaReservationProperties token = new GaReservationProperties();
        token.setInternalToken("tok");
        service = new BookingReissueService(bookings, mock(SeatReservationRepository.class), outbox, mock(EventServiceClient.class),
                tickets, token, kafka, new ObjectMapper());
    }

    private static Booking paid(String id, int quantity) {
        BookingItem item = new BookingItem();
        item.setQuantity(quantity);
        Booking b = new Booking();
        b.setId(id);
        b.setBookingCode("HALO-" + id);
        b.setCustomerId("u-1");
        b.setEventId("ev");
        b.setStatus(BookingStatus.PAID);
        b.setItems(List.of(item));
        return b;
    }

    private void issued(String bookingId, int n) {
        when(tickets.issuedCounts(eq("tok"), any())).thenReturn(Map.of("counts", Map.of(bookingId, n)));
    }

    private void originalMessage(String bookingId) {
        OutboxEvent o = new OutboxEvent();
        o.setPayload("{\"bookingId\":\"" + bookingId + "\"}");
        when(outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(bookingId, "BOOKING_CONFIRMED")).thenReturn(Optional.of(o));
    }

    @Test
    void aPaidOrderWithMissingTicketsIsPublishedAgainAndRecorded() {
        Booking b = paid("b1", 2);
        issued("b1", 0);
        originalMessage("b1");

        var outcome = service.reissue(b);

        assertThat(outcome.result()).isEqualTo(BookingReissueService.Result.REQUESTED);
        verify(kafka).send(eq("haloticket.booking.confirmed.v1"), eq("b1"), anyString());
        verify(bookings).markReissueRequested(eq("b1"), any());
    }

    @Test
    void anOrderThatAlreadyHasAllItsTicketsPublishesNothing() {
        Booking b = paid("b1", 2);
        issued("b1", 2);

        assertThat(service.reissue(b).result()).isEqualTo(BookingReissueService.Result.ALREADY_ISSUED);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void askingAgainWithinThirtySecondsIsTooSoonAndTellsHowLongToWait() {
        Booking b = paid("b1", 1);
        b.setLastReissueAt(LocalDateTime.now().minusSeconds(10));
        issued("b1", 0);

        var outcome = service.reissue(b);

        assertThat(outcome.result()).isEqualTo(BookingReissueService.Result.TOO_SOON);
        assertThat(outcome.retryAfterSeconds()).isBetween(1L, 30L);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void anOrderThatIsNotPaidIsRefusedWithoutAskingTicketService() {
        Booking b = paid("b1", 1);
        b.setStatus(BookingStatus.PENDING);

        assertThat(service.reissue(b).result()).isEqualTo(BookingReissueService.Result.NOT_PAID);
        verify(tickets, never()).issuedCounts(anyString(), any());
    }

    @Test
    void whenTicketServiceCannotCountTheRequestStillGoesOut() {
        Booking b = paid("b1", 1);
        when(tickets.issuedCounts(any(), any())).thenThrow(new IllegalStateException("down"));
        originalMessage("b1");

        assertThat(service.reissue(b).result()).isEqualTo(BookingReissueService.Result.REQUESTED);
    }

    @Test
    void someoneElsesOrderAnswers404ExactlyLikeAMissingOne() {
        Booking b = paid("b1", 1);
        when(bookings.findByBookingCode("HALO-b1")).thenReturn(Optional.of(b));
        when(bookings.findByBookingCode("nope")).thenReturn(Optional.empty());
        when(bookings.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissueForCustomer("HALO-b1", "someone-else")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.reissueForCustomer("nope", "u-1")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
