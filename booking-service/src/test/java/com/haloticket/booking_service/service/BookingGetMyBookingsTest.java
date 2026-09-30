package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.impl.BookingServiceImpl;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookingGetMyBookingsTest {
    @Test
    void includesPendingAndCancelledBookingsWithoutIssuedTicketsForOnlyTheOwner() {
        BookingRepository repository = mock(BookingRepository.class);
        BookingService service = new BookingServiceImpl(repository,
                mock(SeatReservationRepository.class), mock(OutboxEventRepository.class),
                mock(RedissonClient.class), mock(StringRedisTemplate.class),
                mock(ObjectMapper.class), mock(EventServiceClient.class));
        LocalDateTime now = LocalDateTime.of(2026, 9, 29, 12, 0);
        Booking pending = Booking.builder().id("pending-id").bookingCode("HALO-100")
                .customerId("owner-1").eventId("event-1").showtimeId("show-1")
                .status(BookingStatus.PENDING).totalAmount(250000.0).createdAt(now)
                .items(List.of(BookingItem.builder().quantity(2).build()))
                .seatReservations(List.of()).build();
        Booking cancelled = Booking.builder().id("cancelled-id").bookingCode("HALO-099")
                .customerId("owner-1").eventId("event-2")
                .status(BookingStatus.CANCELLED).totalAmount(100000.0).createdAt(now.minusDays(1))
                .items(List.of())
                .seatReservations(List.of(SeatReservation.builder().seatId("A1").build()))
                .build();
        when(repository.findByCustomerIdOrderByCreatedAtDesc("owner-1"))
                .thenReturn(List.of(pending, cancelled));

        List<BookingResponse> result = service.getMyBookings("owner-1");

        assertThat(result).extracting(BookingResponse::getId)
                .containsExactly("pending-id", "cancelled-id");
        assertThat(result).extracting(BookingResponse::getStatus)
                .containsExactly(BookingStatus.PENDING, BookingStatus.CANCELLED);
        assertThat(result).extracting(BookingResponse::getQuantity).containsExactly(2, 1);
        assertThat(result.get(0).getCreatedAt()).isEqualTo(now);
        assertThat(result.get(0).getShowtimeId()).isEqualTo("show-1");
        verify(repository).findByCustomerIdOrderByCreatedAtDesc("owner-1");
        verifyNoMoreInteractions(repository);
    }
}
