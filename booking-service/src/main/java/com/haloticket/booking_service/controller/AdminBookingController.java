package com.haloticket.booking_service.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.dto.response.EventShowtimeDto;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.SeatReservation;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API quản trị để phát lại sự kiện {@code BOOKING_CONFIRMED} lên Kafka cho đơn đã thanh toán,
 * dùng khi ticket-service chưa phát hành vé. Chỉ vai trò ADMIN được gọi.
 */
@RestController
@RequestMapping("/api/bookings/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminBookingController {

    private final BookingRepository bookingRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final EventServiceClient eventServiceClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Phát lại sự kiện xác nhận đơn lên topic {@code haloticket.booking.confirmed.v1}.
     * Ưu tiên payload gốc trong outbox; nếu không có hoặc lỗi parse thì dựng lại từ đơn hàng.
     * Mặc định chạy thử ({@code dryRun=true}), chỉ trả payload mà không gửi Kafka.
     * @param bookingId mã đơn cần phát lại
     * @param dryRun {@code true} để chỉ xem trước, {@code false} để gửi thật
     * @param jwt token của quản trị viên, dùng ghi nhật ký kiểm toán
     * @return payload, trạng thái đơn và thông báo kết quả
     * @throws NoSuchElementException khi không tìm thấy đơn
     * @throws IllegalStateException khi đơn chưa ở trạng thái PAID hoặc CONFIRMED
     */
    @PostMapping("/{bookingId}/republish-confirmed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> republishConfirmed(
            @PathVariable String bookingId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @AuthenticationPrincipal Jwt jwt) {

        String caller = "anonymous";
        if (jwt != null) {
            String email = jwt.getClaimAsString("email");
            caller = (email != null && !email.isBlank()) ? email : jwt.getSubject();
        }

        log.info("[ADMIN_AUDIT] Caller {} requested republish-confirmed for bookingId={}, dryRun={}", caller, bookingId, dryRun);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Only PAID or CONFIRMED bookings can be republished. Current status: " + booking.getStatus());
        }

        Map<String, Object> payloadMap = null;
        Optional<OutboxEvent> originalOutbox = outboxEventRepository
                .findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(booking.getId(), "BOOKING_CONFIRMED");

        if (originalOutbox.isPresent() && originalOutbox.get().getPayload() != null) {
            try {
                payloadMap = objectMapper.readValue(originalOutbox.get().getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse original outbox payload, falling back to reconstruction: {}", e.getMessage());
            }
        }

        if (payloadMap == null) {
            payloadMap = reconstructPayload(booking);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookingId", booking.getId());
        response.put("bookingCode", booking.getBookingCode());
        response.put("status", booking.getStatus().name());
        response.put("dryRun", dryRun);
        response.put("payload", payloadMap);

        if (dryRun) {
            response.put("message", "DRY RUN: Event not published to Kafka. Set query parameter ?dryRun=false to trigger issuance.");
            return ResponseEntity.ok(response);
        }

        try {
            String jsonPayload = objectMapper.writeValueAsString(payloadMap);
            kafkaTemplate.send("haloticket.booking.confirmed.v1", booking.getId(), jsonPayload);
            log.info("[ADMIN_AUDIT] SUCCESS: Republished event haloticket.booking.confirmed.v1 for bookingId={} by caller {}", bookingId, caller);
            response.put("message", "SUCCESS: Event haloticket.booking.confirmed.v1 was republished to Kafka.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ADMIN_AUDIT] FAILED: Could not republish event for bookingId={}", bookingId, e);
            throw new RuntimeException("Failed to republish booking confirmed event to Kafka: " + e.getMessage(), e);
        }
    }

    /**
     * Dựng lại payload {@code BOOKING_CONFIRMED} từ dữ liệu đơn, ghế đã giữ và thông tin sự kiện
     * (lấy từ event-service; nếu lỗi thì bỏ qua các trường sự kiện).
     * @param booking đơn hàng cần dựng payload
     * @return payload dạng map giữ nguyên thứ tự trường
     */
    private Map<String, Object> reconstructPayload(Booking booking) {
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("bookingId", booking.getId());
        payloadMap.put("bookingCode", booking.getBookingCode());
        payloadMap.put("customerId", booking.getCustomerId());
        payloadMap.put("customerEmail", booking.getCustomerEmail());
        payloadMap.put("customerName", booking.getCustomerName());
        payloadMap.put("eventId", booking.getEventId());
        payloadMap.put("showtimeId", booking.getShowtimeId());
        payloadMap.put("amount", booking.getTotalAmount());

        List<String> seatIds = seatReservationRepository.findByBooking(booking)
                .stream().map(SeatReservation::getSeatId).filter(Objects::nonNull).toList();
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
                if (eventStartTime != null) {
                    payloadMap.put("eventStartTime", eventStartTime.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Event details unavailable for booking {}", booking.getId(), e);
        }

        List<Map<String, Object>> itemsList = new ArrayList<>();
        if (booking.getItems() != null) {
            for (BookingItem item : booking.getItems()) {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("ticketTypeId", item.getTicketTypeId());
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
                itemsList.add(itemMap);
            }
        }
        payloadMap.put("items", itemsList);
        return payloadMap;
    }
}
