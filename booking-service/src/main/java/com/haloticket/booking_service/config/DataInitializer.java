package com.haloticket.booking_service.config;

import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final BookingRepository bookingRepository;

    @PersistenceContext
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        if (bookingRepository.existsById("booking-001")) {
            log.info("Booking database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Booking Service...");

        // 1. Confirmed Booking
        Booking confirmedBooking = Booking.builder()
                .id("booking-001")
                .bookingCode("HT-20260829-001")
                .customerId("44444444-4444-4444-4444-444444444444") // Customer User
                .eventId("event-001")
                .voucherId("voucher-01")
                .subtotal(3000000.0)
                .discountAmount(200000.0)
                .totalAmount(2800000.0)
                .status(BookingStatus.PAID)
                .expiredAt(LocalDateTime.now().plusDays(30))
                .build();
        entityManager.persist(confirmedBooking);

        BookingItem item1 = BookingItem.builder()
                .id("bitem-001")
                .booking(confirmedBooking)
                .ticketTypeId("tt-001")
                .quantity(2)
                .unitPrice(1500000.0)
                .subTotal(3000000.0)
                .build();
        entityManager.persist(item1);

        SeatReservation res1 = SeatReservation.builder()
                .id("res-001")
                .booking(confirmedBooking)
                .seatId("seat-a1")
                .status(SeatReservationStatus.CONFIRMED)
                .lockExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
        entityManager.persist(res1);

        SeatReservation res2 = SeatReservation.builder()
                .id("res-002")
                .booking(confirmedBooking)
                .seatId("seat-a2")
                .status(SeatReservationStatus.CONFIRMED)
                .lockExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
        entityManager.persist(res2);

        // 2. Pending Booking
        Booking pendingBooking = Booking.builder()
                .id("booking-002")
                .bookingCode("HT-20260829-002")
                .customerId("44444444-4444-4444-4444-444444444444")
                .eventId("event-001")
                .subtotal(800000.0)
                .discountAmount(0.0)
                .totalAmount(800000.0)
                .status(BookingStatus.PENDING)
                .expiredAt(LocalDateTime.now().plusMinutes(10))
                .build();
        entityManager.persist(pendingBooking);

        BookingItem item2 = BookingItem.builder()
                .id("bitem-002")
                .booking(pendingBooking)
                .ticketTypeId("tt-002")
                .quantity(1)
                .unitPrice(800000.0)
                .subTotal(800000.0)
                .build();
        entityManager.persist(item2);

        SeatReservation res3 = SeatReservation.builder()
                .id("res-003")
                .booking(pendingBooking)
                .seatId("seat-c1")
                .status(SeatReservationStatus.HOLDING)
                .lockExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        entityManager.persist(res3);

        log.info("Booking Service sample data initialized successfully! Created confirmed and pending bookings with items and seat reservations.");
    }
}
