package com.haloticket.booking_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Yêu cầu ticket-service phát hành lại vé của một đơn đã thanh toán. Việc phát hành là idempotent (mỗi vé một khóa phát hành) nên
 * yêu cầu hai lần không tạo vé trùng. Dùng cho chủ đơn (nút "Tải lại" ở trang đơn hàng) và cho bộ lập lịch kích lại các đơn đã thanh toán
 * mà vẫn thiếu vé; nút của admin có endpoint riêng.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingReissueService {

    public static final String TOPIC = "haloticket.booking.confirmed.v1";
    /** Hỏi lại sớm hơn mức này sẽ nhận TOO_SOON: đơn đang được phát hành lại không bị dồn thêm thông điệp. */
    public static final int MIN_SECONDS_BETWEEN_REQUESTS = 30;

    /** Kết quả yêu cầu: đã gửi, đã đủ vé, quá sớm, hoặc đơn chưa thanh toán. */
    public enum Result { REQUESTED, ALREADY_ISSUED, TOO_SOON, NOT_PAID }

    /** Kết quả kèm số giây nên chờ trước khi thử lại (chỉ có khi {@code TOO_SOON}). */
    public record Outcome(Result result, Long retryAfterSeconds) {
        /** Tạo kết quả không kèm thời gian chờ. */
        static Outcome of(Result result) {
            return new Outcome(result, null);
        }
    }

    private final BookingRepository bookings;
    private final SeatReservationRepository seatReservations;
    private final OutboxEventRepository outbox;
    private final EventServiceClient events;
    private final TicketStockClient ticketStock;
    private final GaReservationProperties ticketProperties;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    /** Yêu cầu của chủ đơn. Đơn của người khác trả 404 như trang đơn hàng, để không dò được mã đơn. */
    @Transactional
    public Outcome reissueForCustomer(String bookingCode, String customerId) {
        Booking booking = bookings.findByBookingCode(bookingCode)
                .or(() -> bookings.findById(bookingCode))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        if (booking.getCustomerId() == null || !booking.getCustomerId().equals(customerId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        }
        return reissue(booking);
    }

    /**
     * Phát hành lại vé: nếu đơn đã thanh toán và còn thiếu vé (và đã qua {@code MIN_SECONDS_BETWEEN_REQUESTS} giây từ lần trước) thì gửi lại
     * thông điệp xác nhận lên Kafka và ghi nhận lần yêu cầu.
     * @param booking đơn cần phát hành lại vé
     * @return kết quả {@code Outcome}
     * @throws IllegalStateException khi không gửi được thông điệp lên Kafka
     */
    @Transactional
    public Outcome reissue(Booking booking) {
        if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.CONFIRMED) {
            return Outcome.of(Result.NOT_PAID);
        }
        long expected = expectedTickets(booking);
        Long issued = issuedCount(booking.getId());
        if (issued != null && expected > 0 && issued >= expected) {
            return Outcome.of(Result.ALREADY_ISSUED);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = booking.getLastReissueAt();
        if (last != null) {
            long since = Duration.between(last, now).getSeconds();
            if (since < MIN_SECONDS_BETWEEN_REQUESTS) {
                return new Outcome(Result.TOO_SOON, MIN_SECONDS_BETWEEN_REQUESTS - since);
            }
        }
        try {
            kafka.send(TOPIC, booking.getId(), objectMapper.writeValueAsString(payloadFor(booking)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish the booking confirmation again: " + e.getClass().getSimpleName(), e);
        }
        bookings.markReissueRequested(booking.getId(), now);
        log.info("[REISSUE] requested bookingId={}", booking.getId());
        return Outcome.of(Result.REQUESTED);
    }

    /** Tổng số vé đáng lẽ có của đơn: cộng số lượng các dòng vé. */
    static long expectedTickets(Booking booking) {
        if (booking.getItems() == null) return 0;
        return booking.getItems().stream().mapToLong(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
    }

    /** Số vé đã phát hành cho đơn, hoặc null khi ticket-service không trả lời (khi đó yêu cầu vẫn được gửi đi). */
    private Long issuedCount(String bookingId) {
        String token = ticketProperties.getInternalToken();
        if (token == null || token.isBlank()) return null;
        try {
            Map<String, Map<String, Integer>> answer = ticketStock.issuedCounts(token, Map.of("bookingIds", List.of(bookingId)));
            if (answer == null || answer.get("counts") == null) return null;
            Integer n = answer.get("counts").get(bookingId);
            return n == null ? 0L : n.longValue();
        } catch (RuntimeException e) {
            log.warn("[REISSUE] issued count unavailable: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Thông điệp xác nhận như lúc phát hành lần đầu (lấy từ outbox), nếu không có thì dựng lại từ đơn. */
    Map<String, Object> payloadFor(Booking booking) {
        Optional<OutboxEvent> original = outbox.findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(booking.getId(), "BOOKING_CONFIRMED");
        if (original.isPresent() && original.get().getPayload() != null) {
            try {
                return objectMapper.readValue(original.get().getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("[REISSUE] original payload unreadable, rebuilding: {}", e.getClass().getSimpleName());
            }
        }
        return rebuild(booking);
    }

    /** Dựng lại payload xác nhận từ đơn, ghế đã giữ và thông tin sự kiện (nếu lấy được). */
    private Map<String, Object> rebuild(Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("bookingCode", booking.getBookingCode());
        payload.put("customerId", booking.getCustomerId());
        payload.put("customerEmail", booking.getCustomerEmail());
        payload.put("customerName", booking.getCustomerName());
        payload.put("eventId", booking.getEventId());
        payload.put("showtimeId", booking.getShowtimeId());
        payload.put("amount", booking.getTotalAmount());
        payload.put("seatIds", seatReservations.findByBooking(booking).stream().map(SeatReservation::getSeatId).filter(Objects::nonNull).toList());
        try {
            EventResponse event = events.getEventById(booking.getEventId());
            if (event != null) {
                payload.put("eventTitle", event.getTitle());
                payload.put("locationName", event.getLocationName());
                payload.put("address", event.getAddress());
                EventShowtimeDto showtime = event.getShowtimes() == null ? null : event.getShowtimes().stream()
                        .filter(st -> Objects.equals(st.getId(), booking.getShowtimeId())).findFirst().orElse(null);
                var start = showtime != null ? showtime.getStartTime() : event.getStartTime();
                if (start != null) payload.put("eventStartTime", start.toString());
            }
        } catch (Exception e) {
            log.warn("[REISSUE] event details unavailable for booking {}", booking.getId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (booking.getItems() != null) {
            for (BookingItem item : booking.getItems()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticketTypeId", item.getTicketTypeId());
                row.put("quantity", item.getQuantity());
                row.put("unitPrice", item.getUnitPrice());
                items.add(row);
            }
        }
        payload.put("items", items);
        return payload;
    }
}
