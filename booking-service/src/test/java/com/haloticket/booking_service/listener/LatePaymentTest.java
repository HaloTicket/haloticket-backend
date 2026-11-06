package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.config.LatePaymentProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.GaReservationService;
import com.haloticket.booking_service.service.PaymentExceptionService;
import com.haloticket.booking_service.service.SeatWebsocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A payment that arrives after the booking was cancelled or expired. */
class LatePaymentTest {

    private static final String PAYMENT = "{\"bookingId\":\"b-1\",\"paymentId\":\"pay-1\",\"amount\":200000,\"provider\":\"VNPAY\"}";

    private BookingRepository bookings;
    private SeatReservationRepository seatReservations;
    private OutboxEventRepository outbox;
    private GaReservationService ga;
    private PaymentExceptionService exceptions;
    private BookingEventListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bookings = mock(BookingRepository.class);
        seatReservations = mock(SeatReservationRepository.class);
        outbox = mock(OutboxEventRepository.class);
        ga = mock(GaReservationService.class);
        exceptions = mock(PaymentExceptionService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        listener = new BookingEventListener(bookings, seatReservations, outbox, redis, new ObjectMapper(),
                mock(SeatWebsocketService.class), mock(EventServiceClient.class), ga, exceptions, new LatePaymentProperties());
    }

    private Booking booking(BookingStatus status, Boolean gaReserved) {
        Booking booking = Booking.builder().id("b-1").bookingCode("HALO-1").customerId("owner").eventId("event-1")
                .showtimeId("show-1").totalAmount(200000d).status(status).gaReserved(gaReserved)
                .items(List.of(BookingItem.builder().ticketTypeId("t1").quantity(2).unitPrice(100000d).build())).build();
        when(bookings.findByIdForUpdate("b-1")).thenReturn(Optional.of(booking));
        when(seatReservations.findByBooking(booking)).thenReturn(List.of());
        return booking;
    }

    @Test
    void stockLeftReopensTheHoldConfirmsTheBookingAndWritesOneConfirmationEvenOnRedelivery() throws Exception {
        Booking booking = booking(BookingStatus.CANCELLED, true);
        when(ga.reopen(eq("b-1"), any())).thenReturn(new GaReservationService.ReopenOutcome(true, null));

        Instant before = Instant.now();
        listener.handlePaymentCompleted(PAYMENT);
        listener.handlePaymentCompleted(PAYMENT); // redelivery: the booking is PAID now

        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(ga, times(1)).reopen(eq("b-1"), expiresAt.capture());
        assertThat(Duration.between(before, expiresAt.getValue()).toSeconds()).isBetween(595L, 605L);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(1)).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(saved.getValue().getTopic()).isEqualTo("haloticket.booking.confirmed.v1");
        JsonNode payload = new ObjectMapper().readTree(saved.getValue().getPayload());
        assertThat(payload.get("bookingId").asText()).isEqualTo("b-1");
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        verifyNoInteractions(exceptions);
    }

    @Test
    void expiredBookingIsTreatedLikeACancelledOne() {
        Booking booking = booking(BookingStatus.EXPIRED, true);
        when(ga.reopen(eq("b-1"), any())).thenReturn(new GaReservationService.ReopenOutcome(true, null));

        listener.handlePaymentCompleted(PAYMENT);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
    }

    @Test
    void soldOutRecordsAnOpenExceptionAndLeavesTheBookingCancelled() {
        Booking booking = booking(BookingStatus.CANCELLED, true);
        when(ga.reopen(eq("b-1"), any())).thenReturn(new GaReservationService.ReopenOutcome(false, "TICKET_SOLD_OUT"));

        listener.handlePaymentCompleted(PAYMENT);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outbox, never()).save(any());
        verify(bookings, never()).save(any());
        verify(exceptions).record(eq("b-1"), eq("HALO-1"), eq("pay-1"), eq(PaymentExceptionKind.LATE_PAYMENT_NO_STOCK),
                eq(200000d), eq("VNPAY"), anyString());
    }

    @Test
    void notReopenableRecordsNoStock() {
        booking(BookingStatus.CANCELLED, true);
        when(ga.reopen(eq("b-1"), any())).thenReturn(new GaReservationService.ReopenOutcome(false, "RESERVATION_NOT_REOPENABLE"));

        listener.handlePaymentCompleted(PAYMENT);

        verify(exceptions).record(eq("b-1"), any(), eq("pay-1"), eq(PaymentExceptionKind.LATE_PAYMENT_NO_STOCK), any(), any(), anyString());
    }

    @Test
    void bookingWithSeatsRecordsSeatsUnavailableAndNeverTouchesStock() {
        Booking booking = booking(BookingStatus.CANCELLED, true); // mixed: stock hold and a real seat
        SeatReservation seat = SeatReservation.builder().booking(booking).seatId("seat_1_2")
                .status(SeatReservationStatus.CANCELLED).build();
        when(seatReservations.findByBooking(booking)).thenReturn(List.of(seat));

        listener.handlePaymentCompleted(PAYMENT);

        verifyNoInteractions(ga);
        verify(outbox, never()).save(any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(exceptions).record(eq("b-1"), eq("HALO-1"), eq("pay-1"), eq(PaymentExceptionKind.LATE_PAYMENT_SEATS_UNAVAILABLE),
                eq(200000d), eq("VNPAY"), anyString());
    }

    @Test
    void unreachableTicketServicePropagatesSoKafkaRetries() {
        Booking booking = booking(BookingStatus.CANCELLED, true);
        when(ga.reopen(eq("b-1"), any())).thenThrow(new IllegalStateException("TICKET_STOCK_UNAVAILABLE"));

        assertThatThrownBy(() -> listener.handlePaymentCompleted(PAYMENT)).isInstanceOf(IllegalStateException.class);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outbox, never()).save(any());
        verifyNoInteractions(exceptions);
    }

    @Test
    void bookingWithoutAStockHoldRecordsNoStock() {
        booking(BookingStatus.CANCELLED, null);

        listener.handlePaymentCompleted(PAYMENT);

        verifyNoInteractions(ga);
        verify(exceptions).record(eq("b-1"), any(), eq("pay-1"), eq(PaymentExceptionKind.LATE_PAYMENT_NO_STOCK), any(), any(), anyString());
    }

    @Test
    void refundedOrOtherUnexpectedStatusIsRecorded() {
        booking(BookingStatus.REFUNDED, true);

        listener.handlePaymentCompleted(PAYMENT);

        verifyNoInteractions(ga);
        verify(exceptions).record(eq("b-1"), any(), eq("pay-1"), eq(PaymentExceptionKind.LATE_PAYMENT_UNEXPECTED_STATUS),
                eq(200000d), eq("VNPAY"), anyString());
    }

    @Test
    void paidOrConfirmedBookingIsIgnored() {
        for (BookingStatus status : List.of(BookingStatus.PAID, BookingStatus.CONFIRMED)) {
            booking(status, true);
            listener.handlePaymentCompleted(PAYMENT);
        }
        verifyNoInteractions(ga, exceptions);
        verify(outbox, never()).save(any());
        verify(bookings, never()).save(any());
    }

    @Test
    void amountFallsBackToTheBookingTotalWhenThePayloadHasNone() {
        booking(BookingStatus.CANCELLED, true);
        when(ga.reopen(eq("b-1"), any())).thenReturn(new GaReservationService.ReopenOutcome(false, "TICKET_SOLD_OUT"));

        listener.handlePaymentCompleted("{\"bookingId\":\"b-1\"}");

        verify(exceptions).record(eq("b-1"), any(), eq(""), eq(PaymentExceptionKind.LATE_PAYMENT_NO_STOCK), eq(200000d), eq(""), anyString());
    }
}
