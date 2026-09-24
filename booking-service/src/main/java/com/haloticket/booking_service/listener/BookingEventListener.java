package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.service.SeatWebsocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingEventListener {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SeatWebsocketService websocketService;

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

            String provider = node.has("provider") ? node.get("provider").asText() : "UNKNOWN";

            Booking booking = bookingRepository.findById(bookingId).orElse(null);
            if (booking == null) {
                log.warn("Booking not found for payment completed event: {}", bookingId);
                return;
            }

            booking.setStatus(BookingStatus.PAID);
            bookingRepository.save(booking);

            List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
            List<String> seatIds = new ArrayList<>();
            for (SeatReservation res : reservations) {
                res.setStatus(SeatReservationStatus.SOLD);
                seatIds.add(res.getSeatId());
                
                String showtimeId = booking.getShowtimeId() != null ? booking.getShowtimeId() : res.getShowtimeId();
                String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + booking.getEventId();
                
                // mark seat as sold in Redis
                String soldKey = "seat:sold:" + showtimePrefix + ":" + res.getSeatId();
                stringRedisTemplate.opsForValue().set(soldKey, booking.getId());
                stringRedisTemplate.opsForSet().add(showtimePrefix + ":sold_seats", res.getSeatId());

                // remove hold key and remove from held_seats set
                String holdKey = "seat:hold:" + showtimePrefix + ":" + res.getSeatId();
                stringRedisTemplate.delete(holdKey);
                stringRedisTemplate.opsForSet().remove(showtimePrefix + ":held_seats", res.getSeatId());

                // also ensure showtime keys and event keys are cleanly synced
                if (showtimeId != null) {
                    stringRedisTemplate.delete("seat:hold:showtime:" + showtimeId + ":" + res.getSeatId());
                    stringRedisTemplate.opsForSet().remove("showtime:" + showtimeId + ":held_seats", res.getSeatId());
                    stringRedisTemplate.opsForSet().add("showtime:" + showtimeId + ":sold_seats", res.getSeatId());
                    stringRedisTemplate.opsForValue().set("seat:sold:showtime:" + showtimeId + ":" + res.getSeatId(), booking.getId());
                }
                stringRedisTemplate.delete("seat:hold:event:" + booking.getEventId() + ":" + res.getSeatId());
                stringRedisTemplate.opsForSet().remove("event:" + booking.getEventId() + ":held_seats", res.getSeatId());
                
                // broadcast websocket
                websocketService.broadcastSeatStatus(booking.getEventId(), showtimeId, res.getSeatId(), "SOLD");
            }
            seatReservationRepository.saveAll(reservations);
            
            // publish booking.confirmed event for ticket-service and notification-service
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("bookingId", booking.getId());
            payloadMap.put("bookingCode", booking.getBookingCode());
            payloadMap.put("customerId", booking.getCustomerId());
            payloadMap.put("customerEmail", booking.getCustomerEmail());
            payloadMap.put("customerName", booking.getCustomerName());
            payloadMap.put("eventId", booking.getEventId());
            payloadMap.put("showtimeId", booking.getShowtimeId());
            payloadMap.put("amount", booking.getTotalAmount());
            payloadMap.put("seatIds", seatIds);
            
            List<Map<String, Object>> itemsList = new ArrayList<>();
            if (booking.getItems() != null) {
                for (BookingItem item : booking.getItems()) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("ticketTypeId", item.getTicketTypeId());
                    itemMap.put("quantity", item.getQuantity());
                    itemsList.add(itemMap);
                }
            }
            payloadMap.put("items", itemsList);
            
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("BOOKING")
                    .aggregateId(booking.getId())
                    .eventType("BOOKING_CONFIRMED")
                    .topic("haloticket.booking.confirmed.v1")
                    .payload(objectMapper.writeValueAsString(payloadMap))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
            outboxEventRepository.save(outboxEvent);

            log.info("Booking {} confirmed and booking.confirmed outbox event published", bookingId);

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
                String showtimeId = booking.getShowtimeId() != null ? booking.getShowtimeId() : res.getShowtimeId();
                String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + booking.getEventId();
                String holdKey = "seat:hold:" + showtimePrefix + ":" + res.getSeatId();
                stringRedisTemplate.delete(holdKey);
                stringRedisTemplate.opsForSet().remove(showtimePrefix + ":held_seats", res.getSeatId());
                if (showtimeId != null) {
                    stringRedisTemplate.delete("seat:hold:showtime:" + showtimeId + ":" + res.getSeatId());
                    stringRedisTemplate.opsForSet().remove("showtime:" + showtimeId + ":held_seats", res.getSeatId());
                }
                stringRedisTemplate.delete("seat:hold:event:" + booking.getEventId() + ":" + res.getSeatId());
                stringRedisTemplate.opsForSet().remove("event:" + booking.getEventId() + ":held_seats", res.getSeatId());
                
                // broadcast WebSocket
                websocketService.broadcastSeatStatus(booking.getEventId(), showtimeId, res.getSeatId(), "AVAILABLE");
            }
            seatReservationRepository.saveAll(reservations);
            log.info("Booking {} cancelled after payment failed", bookingId);

        } catch (Exception e) {
            log.error("Error processing PAYMENT_FAILED event", e);
        }
    }
}
