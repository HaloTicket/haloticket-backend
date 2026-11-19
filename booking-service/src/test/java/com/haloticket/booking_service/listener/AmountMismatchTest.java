package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.config.LatePaymentProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.GaReservationService;
import com.haloticket.booking_service.service.PaymentExceptionService;
import com.haloticket.booking_service.service.SeatWebsocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Thanh toán có số tiền lệch tổng tiền của đơn không được xác nhận đơn: chỉ ghi ngoại lệ để quản trị viên xử lý. */
class AmountMismatchTest {

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

    private Booking booking(BookingStatus status) {
        Booking booking = Booking.builder().id("b-1").bookingCode("HALO-1").customerId("owner").eventId("event-1")
                .showtimeId("show-1").totalAmount(3_000_000d).status(status).gaReserved(true)
                .items(List.of(BookingItem.builder().ticketTypeId("t1").quantity(1).unitPrice(3_000_000d).build())).build();
        when(bookings.findByIdForUpdate("b-1")).thenReturn(Optional.of(booking));
        when(seatReservations.findByBooking(booking)).thenReturn(List.of());
        return booking;
    }

    private static String payment(String amountJson) {
        return "{\"bookingId\":\"b-1\",\"paymentId\":\"pay-1\",\"provider\":\"SEPAY\"" + amountJson + "}";
    }

    @Test
    void underpaidPendingBookingStaysPendingAndRecordsAnException() {
        Booking booking = booking(BookingStatus.PENDING);

        listener.handlePaymentCompleted(payment(",\"amount\":1000"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(bookings, never()).save(any());
        verify(outbox, never()).save(any());
        verify(exceptions).record(eq("b-1"), eq("HALO-1"), eq("pay-1"), eq(PaymentExceptionKind.AMOUNT_MISMATCH),
                eq(1000d), eq("SEPAY"), anyString());
    }

    @Test
    void overpaymentIsAlsoAMismatch() {
        Booking booking = booking(BookingStatus.PENDING);

        listener.handlePaymentCompleted(payment(",\"amount\":3500000"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(exceptions).record(eq("b-1"), any(), eq("pay-1"), eq(PaymentExceptionKind.AMOUNT_MISMATCH), eq(3_500_000d), any(), anyString());
    }

    @Test
    void underpaymentOnAnExpiredBookingDoesNotReopenStockEither() {
        Booking booking = booking(BookingStatus.EXPIRED);

        listener.handlePaymentCompleted(payment(",\"amount\":1000"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(ga, never()).reopen(anyString(), any());
        verify(exceptions).record(eq("b-1"), any(), eq("pay-1"), eq(PaymentExceptionKind.AMOUNT_MISMATCH), any(), any(), anyString());
    }

    @Test
    void exactAmountConfirmsTheBooking() {
        Booking booking = booking(BookingStatus.PENDING);

        listener.handlePaymentCompleted(payment(",\"amount\":3000000"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        verify(bookings, times(1)).save(booking);
        verify(exceptions, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void subDongRoundingIsTolerated() {
        Booking booking = booking(BookingStatus.PENDING);

        listener.handlePaymentCompleted(payment(",\"amount\":3000000.4"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
    }

    @Test
    void eventWithoutAnAmountKeepsTheOldBehaviour() {
        Booking booking = booking(BookingStatus.PENDING);

        listener.handlePaymentCompleted(payment(""));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
    }
}
