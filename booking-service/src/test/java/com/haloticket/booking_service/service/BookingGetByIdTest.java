package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.service.impl.BookingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BookingGetByIdTest {

    private BookingRepository bookingRepository;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        bookingService = new BookingServiceImpl(
                bookingRepository,
                mock(SeatReservationRepository.class),
                mock(OutboxEventRepository.class),
                mock(RedissonClient.class),
                mock(StringRedisTemplate.class),
                mock(ObjectMapper.class),
                mock(EventServiceClient.class)
        );
    }

    @Test
    void getBookingById_foundByUuid() {
        Booking booking = Booking.builder()
                .id("4c412e9d-c26d-41f8-a0d5-e80ed516d1bc")
                .bookingCode("BK-2026-001")
                .customerId("cust-1")
                .customerName("Nguyen Van A")
                .customerEmail("a@example.com")
                .eventId("evt-100")
                .totalAmount(250000.0)
                .status(BookingStatus.PAID)
                .expiredAt(LocalDateTime.now().plusHours(1))
                .build();

        when(bookingRepository.findById("4c412e9d-c26d-41f8-a0d5-e80ed516d1bc")).thenReturn(Optional.of(booking));

        BookingResponse res = bookingService.getBookingById("4c412e9d-c26d-41f8-a0d5-e80ed516d1bc");
        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo("4c412e9d-c26d-41f8-a0d5-e80ed516d1bc");
        assertThat(res.getCustomerName()).isEqualTo("Nguyen Van A");
        assertThat(res.getCustomerEmail()).isEqualTo("a@example.com");
        assertThat(res.getEventId()).isEqualTo("evt-100");
    }

    @Test
    void getBookingById_foundByBookingCodeFallback() {
        Booking booking = Booking.builder()
                .id("b-uuid")
                .bookingCode("BK-CODE-99")
                .customerId("cust-2")
                .customerName("Tran Van B")
                .customerEmail("b@example.com")
                .eventId("evt-200")
                .totalAmount(100000.0)
                .status(BookingStatus.PENDING)
                .build();

        when(bookingRepository.findById("BK-CODE-99")).thenReturn(Optional.empty());
        when(bookingRepository.findByBookingCode("BK-CODE-99")).thenReturn(Optional.of(booking));

        BookingResponse res = bookingService.getBookingById("BK-CODE-99");
        assertThat(res).isNotNull();
        assertThat(res.getBookingCode()).isEqualTo("BK-CODE-99");
        assertThat(res.getCustomerName()).isEqualTo("Tran Van B");
    }

    @Test
    void getBookingById_notFoundThrows404() {
        when(bookingRepository.findById("non-existent")).thenReturn(Optional.empty());
        when(bookingRepository.findByBookingCode("non-existent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getBookingById("non-existent"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
