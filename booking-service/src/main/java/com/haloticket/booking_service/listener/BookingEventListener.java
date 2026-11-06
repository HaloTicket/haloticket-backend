package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.dto.response.EventShowtimeDto;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.enums.SeatReservationStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.config.LatePaymentProperties;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.service.GaReservationService;
import com.haloticket.booking_service.service.PaymentExceptionService;
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

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

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
    private final EventServiceClient eventServiceClient;
    private final GaReservationService gaReservationService;
    private final PaymentExceptionService paymentExceptionService;
    private final LatePaymentProperties latePaymentProperties;

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

            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null) {
                log.warn("Booking not found for payment completed event: {}", bookingId);
                return;
            }
            if (booking.getStatus() != BookingStatus.PENDING) {
                handlePaymentForClosedBooking(booking, node);
                return;
            }

            booking.setStatus(BookingStatus.PAID);
            bookingRepository.save(booking);
            confirmPaidBooking(booking, seatReservationRepository.findByBooking(booking));

        } catch (Exception e) {
            log.error("Error processing PAYMENT_COMPLETED event", e);
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Error processing PAYMENT_COMPLETED event", e);
        }
    }

    /**
     * Shared by the normal payment and a late payment that re-opened the stock: seats become SOLD and the
     * BOOKING_CONFIRMED outbox event is written. The booking is already PAID and saved; same transaction.
     */
    private void confirmPaidBooking(Booking booking, List<SeatReservation> reservations) throws JsonProcessingException {
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
        try {
            EventResponse event = eventServiceClient.getEventById(booking.getEventId());
            if (event != null) {
                payloadMap.put("eventTitle", event.getTitle());
                payloadMap.put("locationName", event.getLocationName());
                payloadMap.put("address", event.getAddress());
                EventShowtimeDto showtime = event.getShowtimes() == null ? null : event.getShowtimes().stream()
                        .filter(st -> Objects.equals(st.getId(), booking.getShowtimeId()))
                        .findFirst().orElse(null);
                var eventStartTime = showtime != null ? showtime.getStartTime() : event.getStartTime();
                if (eventStartTime != null) payloadMap.put("eventStartTime", eventStartTime.toString());
            }
        } catch (RuntimeException e) {
            log.warn("Event details unavailable for booking {}", booking.getId(), e);
        }
        
        List<Map<String, Object>> itemsList = new ArrayList<>();
        if (booking.getItems() != null) {
            for (BookingItem item : booking.getItems()) {
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("ticketTypeId", item.getTicketTypeId());
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
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

        log.info("Booking {} confirmed and booking.confirmed outbox event published", booking.getId());
    }

    /**
     * A payment arrived for a booking that is no longer PENDING. PAID/CONFIRMED: already applied (a redelivery), ignore.
     * CANCELLED/EXPIRED: a general-admission-only order takes its stock back and becomes PAID if there is stock left;
     * otherwise, and for orders with seats, an OPEN payment exception asks an admin to refund by hand. Any other status is
     * recorded too. A ticket-service that cannot be reached throws, so Kafka delivers the message again.
     */
    private void handlePaymentForClosedBooking(Booking booking, JsonNode node) throws JsonProcessingException {
        String bookingId = booking.getId();
        BookingStatus status = booking.getStatus();
        String paymentId = node.path("paymentId").asText("");
        String provider = node.path("provider").asText("");
        Double amount = booking.getTotalAmount();
        if (node.hasNonNull("amount") && node.get("amount").isNumber()) amount = node.get("amount").asDouble();

        if (status == BookingStatus.PAID || status == BookingStatus.CONFIRMED) {
            log.info("[LATE_PAYMENT] bookingId={} status={} paymentId={}: payment already applied, ignoring", bookingId, status, paymentId);
            return;
        }
        if (status != BookingStatus.CANCELLED && status != BookingStatus.EXPIRED) {
            log.warn("[LATE_PAYMENT] bookingId={} status={} paymentId={} amount={} provider={}: unexpected status, recorded for an admin",
                    bookingId, status, paymentId, amount, provider);
            paymentExceptionService.record(bookingId, booking.getBookingCode(), paymentId,
                    PaymentExceptionKind.LATE_PAYMENT_UNEXPECTED_STATUS, amount, provider, "Booking status " + status);
            return;
        }

        List<SeatReservation> reservations = seatReservationRepository.findByBooking(booking);
        boolean hasSeats = reservations.stream().anyMatch(r -> r.getSeatId() != null && !r.getSeatId().isBlank());
        if (hasSeats) {
            log.warn("[LATE_PAYMENT] bookingId={} status={} paymentId={} amount={} provider={}: seats cannot be confirmed, recorded for an admin",
                    bookingId, status, paymentId, amount, provider);
            paymentExceptionService.record(bookingId, booking.getBookingCode(), paymentId,
                    PaymentExceptionKind.LATE_PAYMENT_SEATS_UNAVAILABLE, amount, provider,
                    "Booking " + status + " with seats; the seats may have been sold again");
            return;
        }
        if (!Boolean.TRUE.equals(booking.getGaReserved())) {
            log.warn("[LATE_PAYMENT] bookingId={} status={} paymentId={} amount={} provider={}: no stock hold to reopen, recorded for an admin",
                    bookingId, status, paymentId, amount, provider);
            paymentExceptionService.record(bookingId, booking.getBookingCode(), paymentId,
                    PaymentExceptionKind.LATE_PAYMENT_NO_STOCK, amount, provider, "Booking " + status + " without a stock hold");
            return;
        }

        // throws when ticket-service cannot be reached: the message is delivered again
        GaReservationService.ReopenOutcome outcome = gaReservationService.reopen(bookingId,
                Instant.now().plusSeconds(60L * Math.max(1, latePaymentProperties.getReopenMinutes())));
        if (!outcome.reopened()) {
            log.warn("[LATE_PAYMENT] bookingId={} status={} paymentId={} amount={} provider={}: no stock ({}), recorded for an admin",
                    bookingId, status, paymentId, amount, provider, outcome.code());
            paymentExceptionService.record(bookingId, booking.getBookingCode(), paymentId,
                    PaymentExceptionKind.LATE_PAYMENT_NO_STOCK, amount, provider,
                    "Booking " + status + "; stock not reopened: " + outcome.code());
            return;
        }
        log.info("[LATE_PAYMENT] bookingId={} status={} paymentId={}: stock reopened, booking confirmed", bookingId, status, paymentId);
        booking.setStatus(BookingStatus.PAID);
        bookingRepository.save(booking);
        confirmPaidBooking(booking, reservations);
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

            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            if (booking == null) return;
            if (booking.getStatus() != BookingStatus.PENDING) {
                log.info("Ignoring payment failure for booking {} in status {}", bookingId, booking.getStatus());
                return;
            }

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
            gaReservationService.releaseIfReserved(booking);
            log.info("Booking {} cancelled after payment failed", bookingId);

        } catch (Exception e) {
            log.error("Error processing PAYMENT_FAILED event", e);
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Error processing PAYMENT_FAILED event", e);
        }
    }
}
