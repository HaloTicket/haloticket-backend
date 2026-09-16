package com.haloticket.booking_service.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingExpirationScheduler {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 30000) // Check every 30 seconds
    @Transactional
    public void cancelExpiredBookings() {
        LocalDateTime now = LocalDateTime.now();
        List<Booking> expiredBookings = bookingRepository.findByStatusAndExpiredAtBefore(BookingStatus.PENDING, now);

        if (expiredBookings.isEmpty()) {
            return;
        }

        log.info("Found {} expired PENDING bookings", expiredBookings.size());

        for (Booking booking : expiredBookings) {
            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);

            List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
            for (SeatReservation res : reservations) {
                res.setStatus(SeatReservationStatus.CANCELLED);
                String holdKey = "seat:hold:" + booking.getEventId() + ":" + res.getSeatId();
                stringRedisTemplate.delete(holdKey);
            }
            seatReservationRepository.saveAll(reservations);

            try {
                Map<String, Object> payloadMap = new HashMap<>();
                payloadMap.put("bookingId", booking.getId());
                payloadMap.put("bookingCode", booking.getBookingCode());
                payloadMap.put("eventId", booking.getEventId());
                payloadMap.put("customerId", booking.getCustomerId());
                payloadMap.put("reason", "EXPIRED_UNPAID");

                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("BOOKING")
                        .aggregateId(booking.getId())
                        .eventType("BOOKING_CANCELLED")
                        .topic("haloticket.booking.cancelled.v1")
                        .payload(objectMapper.writeValueAsString(payloadMap))
                        .status(OutboxStatus.PENDING)
                        .retryCount(0)
                        .build();

                outboxEventRepository.save(outboxEvent);

            } catch (Exception e) {
                log.error("Error writing outbox event for expired booking {}", booking.getId(), e);
            }
        }
    }
}
