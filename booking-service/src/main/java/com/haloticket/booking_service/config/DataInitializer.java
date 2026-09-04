package com.haloticket.booking_service.config;

import com.haloticket.booking_service.repository.BookingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

        // Use native SQL to bypass Hibernate @GeneratedValue(UUID) lifecycle with manual IDs
        entityManager.createNativeQuery("""
                INSERT INTO bookings (id, booking_code, customer_id, event_id, voucher_id,
                    subtotal, discount_amount, total_amount, status, expired_at, created_at, updated_at)
                VALUES
                    ('booking-001', 'HT-20260829-001', '44444444-4444-4444-4444-444444444444', 'event-001', 'voucher-01',
                     3000000.0, 200000.0, 2800000.0, 'PAID', NOW() + INTERVAL '30 days', NOW(), NOW()),
                    ('booking-002', 'HT-20260829-002', '44444444-4444-4444-4444-444444444444', 'event-001', NULL,
                     800000.0, 0.0, 800000.0, 'PENDING', NOW() + INTERVAL '10 minutes', NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

        entityManager.createNativeQuery("""
                INSERT INTO booking_items (id, booking_id, ticket_type_id, quantity, unit_price, sub_total)
                VALUES
                    ('bitem-001', 'booking-001', 'tt-001', 2, 1500000.0, 3000000.0),
                    ('bitem-002', 'booking-002', 'tt-002', 1, 800000.0, 800000.0)
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

        entityManager.createNativeQuery("""
                INSERT INTO seat_reservations (id, booking_id, seat_id, status, lock_expires_at)
                VALUES
                    ('res-001', 'booking-001', 'seat-a1', 'CONFIRMED', NOW() + INTERVAL '1 hour'),
                    ('res-002', 'booking-001', 'seat-a2', 'CONFIRMED', NOW() + INTERVAL '1 hour'),
                    ('res-003', 'booking-002', 'seat-c1', 'HOLDING',   NOW() + INTERVAL '10 minutes')
                ON CONFLICT (id) DO NOTHING
                """).executeUpdate();

        log.info("Booking Service sample data initialized successfully! Created confirmed and pending bookings with items and seat reservations.");
    }
}
