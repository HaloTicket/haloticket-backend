package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventListener {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "haloticket.payment.completed.v1", groupId = "booking-service")
    @Transactional
    public void handlePaymentCompleted(String message) {
        try {
            log.info("Received PAYMENT_COMPLETED event: {}", message);
            JsonNode node = objectMapper.readTree(message);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : null;

            if (bookingId == null || bookingId.isBlank()) {
                log.warn("Payment completed message does not contain bookingId: {}", message);
                return;
            }

            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking == null) {
                log.warn("Booking not found for payment completed event: {}", bookingId);
                return;
            }

            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);

            List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
            for (SeatReservation res : reservations) {
                res.setStatus(SeatReservationStatus.SOLD);
                // Mark seat as sold in Redis
                String soldKey = "seat:sold:" + booking.getEventId() + ":" + res.getSeatId();
                stringRedisTemplate.opsForValue().set(soldKey, booking.getId());
                // Remove hold key
                String holdKey = "seat:hold:" + booking.getEventId() + ":" + res.getSeatId();
                stringRedisTemplate.delete(holdKey);
            }
            seatReservationRepository.saveAll(reservations);
            log.info("Booking {} confirmed after successful payment", bookingId);

        } catch (Exception e) {
            log.error("Error processing PAYMENT_COMPLETED event", e);
        }
    }

    @KafkaListener(topics = "haloticket.payment.failed.v1", groupId = "booking-service")
    @Transactional
    public void handlePaymentFailed(String message) {
        try {
            log.info("Received PAYMENT_FAILED event: {}", message);
            JsonNode node = objectMapper.readTree(message);
            String bookingId = node.has("bookingId") ? node.get("bookingId").asText() : null;

            if (bookingId == null || bookingId.isBlank()) {
                return;
            }

            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking == null) return;

            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);

            List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
            for (SeatReservation res : reservations) {
                res.setStatus(SeatReservationStatus.CANCELLED);
                String holdKey = "seat:hold:" + booking.getEventId() + ":" + res.getSeatId();
                stringRedisTemplate.delete(holdKey);
            }
            seatReservationRepository.saveAll(reservations);
            log.info("Booking {} cancelled after payment failed", bookingId);

        } catch (Exception e) {
            log.error("Error processing PAYMENT_FAILED event", e);
        }
    }
}
