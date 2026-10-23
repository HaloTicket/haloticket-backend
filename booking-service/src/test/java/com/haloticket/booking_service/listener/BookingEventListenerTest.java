package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.SeatWebsocketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEventListenerTest {
    @Mock BookingRepository bookings;
    @Mock SeatReservationRepository reservations;
    @Mock OutboxEventRepository outbox;
    @Mock StringRedisTemplate redis;
    @Mock SeatWebsocketService websocket;
    @Mock EventServiceClient events;

    private BookingEventListener listener() {
        return new BookingEventListener(bookings, reservations, outbox, redis, new ObjectMapper(), websocket, events,
                org.mockito.Mockito.mock(com.haloticket.booking_service.service.GaReservationService.class));
    }

    @Test
    void repeatedPaymentCompletionCreatesOneConfirmation() throws Exception {
        Booking booking = Booking.builder().id("booking-1").eventId("event-1").totalAmount(200D)
                .items(List.of(BookingItem.builder().ticketTypeId("type-1").quantity(1).unitPrice(200D).build()))
                .status(BookingStatus.PENDING).build();
        EventResponse event = new EventResponse();
        event.setTitle("Real Event");
        when(bookings.findByIdForUpdate("booking-1")).thenReturn(Optional.of(booking));
        when(reservations.findByBooking(booking)).thenReturn(List.of());
        when(events.getEventById("event-1")).thenReturn(event);

        BookingEventListener listener = listener();
        listener.handlePaymentCompleted("{\"bookingId\":\"booking-1\"}");
        listener.handlePaymentCompleted("{\"bookingId\":\"booking-1\"}");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(1)).save(saved.capture());
        var payload = new ObjectMapper().readTree(saved.getValue().getPayload());
        assertThat(payload.get("eventTitle").asText()).isEqualTo("Real Event");
        assertThat(payload.get("amount").asDouble()).isEqualTo(200D);
        assertThat(payload.get("items").get(0).get("unitPrice").asDouble()).isEqualTo(200D);
        verify(reservations, times(1)).findByBooking(booking);
    }

    @Test
    void failedPaymentCannotCancelPaidBooking() {
        Booking booking = Booking.builder().id("booking-1").status(BookingStatus.PAID).build();
        when(bookings.findByIdForUpdate("booking-1")).thenReturn(Optional.of(booking));

        listener().handlePaymentFailed("{\"bookingId\":\"booking-1\"}");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        verify(bookings, never()).save(any());
        verify(reservations, never()).findByBooking(any());
    }

    @Test
    void failedOutboxWritePropagatesSoDatabaseTransactionCanRollBack() {
        Booking booking = Booking.builder().id("booking-1").status(BookingStatus.PENDING).build();
        when(bookings.findByIdForUpdate("booking-1")).thenReturn(Optional.of(booking));
        when(reservations.findByBooking(booking)).thenReturn(List.of());
        when(outbox.save(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> listener().handlePaymentCompleted("{\"bookingId\":\"booking-1\"}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
