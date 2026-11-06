package com.haloticket.booking_service.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.listener.BookingEventListener;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.scheduler.BookingExpirationScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** What happens to a general-admission stock hold when the booking expires, is cancelled, fails, or is paid late. */
class BookingGaLifecycleTest {

    private static final String TOKEN = "tok";

    private BookingRepository bookings;
    private SeatReservationRepository seatReservations;
    private OutboxEventRepository outbox;
    private StringRedisTemplate redis;
    private TicketStockClient stockClient;
    private GaReservationService ga;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bookings = mock(BookingRepository.class);
        seatReservations = mock(SeatReservationRepository.class);
        outbox = mock(OutboxEventRepository.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        stockClient = mock(TicketStockClient.class);
        GaReservationProperties properties = new GaReservationProperties();
        properties.setInternalToken(TOKEN);
        ga = new GaReservationService(properties, stockClient, new ObjectMapper());

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(BookingEventListener.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(BookingEventListener.class)).detachAppender(logs);
    }

    private static Booking booking(String id, BookingStatus status, Boolean gaReserved) {
        return Booking.builder().id(id).bookingCode("HALO-" + id).customerId("owner").eventId("event-1")
                .showtimeId("show-1").status(status).gaReserved(gaReserved)
                .expiredAt(LocalDateTime.now().minusMinutes(1)).items(List.of()).build();
    }

    private BookingExpirationScheduler scheduler() {
        return new BookingExpirationScheduler(bookings, seatReservations, outbox, redis, new ObjectMapper(), ga);
    }

    private BookingEventListener listener() {
        return new BookingEventListener(bookings, seatReservations, outbox, redis, new ObjectMapper(),
                mock(SeatWebsocketService.class), mock(EventServiceClient.class), ga,
                mock(com.haloticket.booking_service.service.PaymentExceptionService.class),
                new com.haloticket.booking_service.config.LatePaymentProperties());
    }

    // --- expiry ------------------------------------------------------------------

    @Test
    void expiredBookingThatHeldStockReleasesIt() {
        Booking expired = booking("exp-1", BookingStatus.PENDING, true);
        when(bookings.findByStatusAndExpiredAtBefore(eq(BookingStatus.PENDING), any())).thenReturn(List.of(expired));
        when(seatReservations.findByBooking(expired)).thenReturn(List.of());

        scheduler().cancelExpiredBookings();

        assertThat(expired.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(stockClient).release(TOKEN, "exp-1");
        verify(outbox).save(any());
    }

    @Test
    void expiredSeatBookingDoesNotCallTicketStock() {
        Booking expired = booking("exp-2", BookingStatus.PENDING, null);
        SeatReservation seat = SeatReservation.builder().booking(expired).seatId("seat_1_1").showtimeId("show-1")
                .status(SeatReservationStatus.HOLDING).build();
        when(bookings.findByStatusAndExpiredAtBefore(eq(BookingStatus.PENDING), any())).thenReturn(List.of(expired));
        when(seatReservations.findByBooking(expired)).thenReturn(List.of(seat));

        scheduler().cancelExpiredBookings();

        assertThat(seat.getStatus()).isEqualTo(SeatReservationStatus.CANCELLED);
        verifyNoInteractions(stockClient);
    }

    @Test
    void expiryStillCompletesWhenTicketServiceIsDown() {
        Booking expired = booking("exp-3", BookingStatus.PENDING, true);
        when(bookings.findByStatusAndExpiredAtBefore(eq(BookingStatus.PENDING), any())).thenReturn(List.of(expired));
        when(seatReservations.findByBooking(expired)).thenReturn(List.of());
        doThrow(new RuntimeException("down")).when(stockClient).release(anyString(), anyString());

        scheduler().cancelExpiredBookings();

        assertThat(expired.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(outbox).save(any());
    }

    // --- payment failed ----------------------------------------------------------

    @Test
    void paymentFailedCancelsTheBookingAndReleasesStock() {
        Booking pending = booking("pf-1", BookingStatus.PENDING, true);
        when(bookings.findByIdForUpdate("pf-1")).thenReturn(Optional.of(pending));
        when(seatReservations.findByBooking(pending)).thenReturn(List.of());

        listener().handlePaymentFailed("{\"bookingId\":\"pf-1\",\"reason\":\"declined\"}");

        assertThat(pending.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(stockClient).release(TOKEN, "pf-1");
    }

    @Test
    void paymentFailedForAnAlreadyCancelledBookingDoesNothing() {
        Booking cancelled = booking("pf-2", BookingStatus.CANCELLED, true);
        when(bookings.findByIdForUpdate("pf-2")).thenReturn(Optional.of(cancelled));

        listener().handlePaymentFailed("{\"bookingId\":\"pf-2\"}");

        verifyNoInteractions(stockClient);
    }

    // --- payment completed -------------------------------------------------------

    @Test
    void latePaymentAfterExpiryWithStockLeftReopensTheHoldAndConfirms() {
        Booking expired = booking("late-1", BookingStatus.CANCELLED, true);
        when(bookings.findByIdForUpdate("late-1")).thenReturn(Optional.of(expired));
        when(seatReservations.findByBooking(expired)).thenReturn(List.of());

        listener().handlePaymentCompleted("{\"bookingId\":\"late-1\",\"paymentId\":\"pay-9\",\"amount\":200000,\"provider\":\"VNPAY\"}");

        verify(stockClient).reopen(eq(TOKEN), eq("late-1"), any());
        assertThat(expired.getStatus()).isEqualTo(BookingStatus.PAID);
        verify(outbox, times(1)).save(any());
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("[LATE_PAYMENT]")
                && e.getFormattedMessage().contains("bookingId=late-1") && e.getFormattedMessage().contains("paymentId=pay-9"));
    }

    @Test
    void paymentOfAPaidBookingIsStillJustIgnored() {
        Booking paid = booking("paid-1", BookingStatus.PAID, true);
        when(bookings.findByIdForUpdate("paid-1")).thenReturn(Optional.of(paid));

        listener().handlePaymentCompleted("{\"bookingId\":\"paid-1\"}");

        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("[LATE_PAYMENT]")
                && !e.getLevel().toString().equals("INFO"));
        verify(outbox, never()).save(any());
    }

    @Test
    void successfulPaymentConfirmsWithoutTouchingStockHere() {
        Booking pending = booking("ok-1", BookingStatus.PENDING, true);
        pending.setCustomerEmail("buyer@example.com");
        when(bookings.findByIdForUpdate("ok-1")).thenReturn(Optional.of(pending));
        when(seatReservations.findByBooking(pending)).thenReturn(List.of());

        listener().handlePaymentCompleted("{\"bookingId\":\"ok-1\",\"paymentId\":\"p\"}");

        assertThat(pending.getStatus()).isEqualTo(BookingStatus.PAID);
        verify(outbox).save(any());
        // ticket-service consumes the hold while issuing, in the same transaction as the stock decrement
        verifyNoInteractions(stockClient);
    }

    // --- seat hold endpoint ---------------------------------------


    @Test
    void realSeatHoldGoesThroughRedis() {
        var redisson = mock(org.redisson.api.RedissonClient.class);
        when(redisson.getLock(anyString())).thenThrow(new IllegalStateException("redis reached"));
        SeatHoldService holds = new SeatHoldService(redisson, mock(SeatWebsocketService.class));

        assertThatThrownBy(() -> holds.holdSeat("event-1", "show-1", "seat_1_2", "u1"))
                .hasMessage("redis reached");
    }

    // --- cancel -------------------------------------------------------------------

    private BookingCancellationService cancellation() {
        return new BookingCancellationService(bookings, seatReservations, outbox, redis, new ObjectMapper(),
                mock(SeatWebsocketService.class), ga);
    }

    @Test
    void ownerCancelsPendingBookingAndStockAndSeatsAreReleased() {
        Booking pending = booking("c-1", BookingStatus.PENDING, true);
        SeatReservation seat = SeatReservation.builder().booking(pending).seatId("seat_1_1").showtimeId("show-1")
                .status(SeatReservationStatus.HOLDING).build();
        when(bookings.findByIdForUpdate("c-1")).thenReturn(Optional.of(pending));
        when(seatReservations.findByBooking(pending)).thenReturn(List.of(seat));

        var response = cancellation().cancelOwnBooking("c-1", "owner");

        assertThat(response.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seat.getStatus()).isEqualTo(SeatReservationStatus.CANCELLED);
        verify(redis).delete("seat:hold:showtime:show-1:seat_1_1");
        verify(stockClient).release(TOKEN, "c-1");
        verify(outbox).save(any());
    }

    @Test
    void cancellingSomeoneElsesBookingLooksLikeNotFound() {
        Booking pending = booking("c-2", BookingStatus.PENDING, true);
        when(bookings.findByIdForUpdate("c-2")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> cancellation().cancelOwnBooking("c-2", "someone-else"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(pending.getStatus()).isEqualTo(BookingStatus.PENDING);
        verifyNoInteractions(stockClient);
    }

    @Test
    void paidBookingCannotBeCancelled() {
        Booking paid = booking("c-3", BookingStatus.PAID, true);
        when(bookings.findByIdForUpdate("c-3")).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> cancellation().cancelOwnBooking("c-3", "owner"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("BOOKING_NOT_CANCELLABLE");
                });
        assertThat(paid.getStatus()).isEqualTo(BookingStatus.PAID);
    }

    @Test
    void cancellingTwiceIsHarmless() {
        Booking cancelled = booking("c-4", BookingStatus.CANCELLED, true);
        when(bookings.findByIdForUpdate("c-4")).thenReturn(Optional.of(cancelled));

        var response = cancellation().cancelOwnBooking("c-4", "owner");

        assertThat(response.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verifyNoInteractions(stockClient);
        verify(outbox, never()).save(any());
    }

    @Test
    void unknownBookingIsNotFound() {
        when(bookings.findByIdForUpdate("nope")).thenReturn(Optional.empty());
        when(bookings.findByBookingCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cancellation().cancelOwnBooking("nope", "owner"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static BookingStatus eq(BookingStatus value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
