package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketServiceClient;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.dto.response.TicketTypeDto;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BookingGetByIdTest {

    private BookingRepository bookingRepository;
    private TicketServiceClient ticketServiceClient;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        ticketServiceClient = mock(TicketServiceClient.class);
        bookingService = new BookingServiceImpl(
                bookingRepository,
                mock(SeatReservationRepository.class),
                mock(OutboxEventRepository.class),
                mock(RedissonClient.class),
                mock(StringRedisTemplate.class),
                mock(ObjectMapper.class),
                mock(EventServiceClient.class),
                ticketServiceClient
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

    @Test
    void getBookingByCode_successWhenOwnerMatches() {
        BookingItem item1 = BookingItem.builder()
                .ticketTypeId("tt-1")
                .quantity(2)
                .unitPrice(100000.0)
                .build();

        Booking booking = Booking.builder()
                .id("booking-uuid-1")
                .bookingCode("BK-CODE-100")
                .customerId("cust-owner")
                .eventId("evt-1")
                .showtimeId("st-1")
                .totalAmount(200000.0)
                .status(BookingStatus.PAID)
                .items(List.of(item1))
                .createdAt(LocalDateTime.now())
                .build();

        TicketTypeDto ttDto = new TicketTypeDto();
        ttDto.setId("tt-1");
        ttDto.setName("Vé VIP");

        when(bookingRepository.findByBookingCode("BK-CODE-100")).thenReturn(Optional.of(booking));
        when(ticketServiceClient.getTicketTypeById("tt-1")).thenReturn(ttDto);

        BookingDetailResponse res = bookingService.getBookingByCode("BK-CODE-100", "cust-owner");

        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo("booking-uuid-1");
        assertThat(res.getBookingCode()).isEqualTo("BK-CODE-100");
        assertThat(res.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(res.getEventId()).isEqualTo("evt-1");
        assertThat(res.getShowtimeId()).isEqualTo("st-1");
        assertThat(res.getTotalAmount()).isEqualTo(200000.0);
        assertThat(res.getItems()).hasSize(1);
        assertThat(res.getItems().get(0).getTicketTypeName()).isEqualTo("Vé VIP");
        assertThat(res.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(res.getItems().get(0).getUnitPrice()).isEqualTo(100000.0);
    }

    @Test
    void getBookingByCode_throws404WhenOwnerMismatch() {
        Booking booking = Booking.builder()
                .id("booking-uuid-2")
                .bookingCode("BK-CODE-200")
                .customerId("cust-owner")
                .status(BookingStatus.PAID)
                .build();

        when(bookingRepository.findByBookingCode("BK-CODE-200")).thenReturn(Optional.of(booking));

        // When customerId does not match, return 404 to avoid enumeration
        assertThatThrownBy(() -> bookingService.getBookingByCode("BK-CODE-200", "other-user"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getBookingByCode_foundByUuid() {
        Booking booking = Booking.builder()
                .id("booking-uuid-3")
                .bookingCode("BK-CODE-300")
                .customerId("cust-owner")
                .status(BookingStatus.PAID)
                .build();

        when(bookingRepository.findByBookingCode("booking-uuid-3")).thenReturn(Optional.empty());
        when(bookingRepository.findById("booking-uuid-3")).thenReturn(Optional.of(booking));

        BookingDetailResponse res = bookingService.getBookingByCode("booking-uuid-3", "cust-owner");

        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo("booking-uuid-3");
        assertThat(res.getBookingCode()).isEqualTo("BK-CODE-300");
    }

    @Test
    void getBookingByCode_throws404WhenNotFound() {
        when(bookingRepository.findByBookingCode("NON-EXISTENT")).thenReturn(Optional.empty());
        when(bookingRepository.findById("NON-EXISTENT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getBookingByCode("NON-EXISTENT", "any-user"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
