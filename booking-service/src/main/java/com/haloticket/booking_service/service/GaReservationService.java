package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.entity.Booking;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Giữ tồn kho cho đơn vé vào cổng tự do (không có sơ đồ ghế). ticket-service giữ số liệu tồn kho; lớp này quyết định
 * phần nào của đơn là vé vào cổng tự do và gọi reserve/release.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GaReservationService {

    /** Một dòng vé vào cổng tự do: loại vé và số lượng cần giữ. */
    public record GaItem(String ticketTypeId, int quantity) {}

    /** Kế hoạch tách đơn: phần giữ theo tồn kho (gaItems) và phần còn đi qua luồng giữ ghế (seatIds). */
    public record Plan(List<GaItem> gaItems, List<String> seatIds) {
        /** Đơn có phần vé vào cổng tự do hay không. */
        public boolean hasGa() {
            return gaItems != null && !gaItems.isEmpty();
        }

        /** Đơn có phần ghế cần giữ theo luồng từng ghế hay không. */
        public boolean hasSeats() {
            return seatIds != null && !seatIds.isEmpty();
        }
    }

    private final GaReservationProperties properties;
    private final TicketStockClient ticketStockClient;
    private final ObjectMapper objectMapper;

    /** Khi ứng dụng khởi động xong, ghi log lỗi nếu token nội bộ để gọi ticket-service bị trống (khi đó mọi lần giữ vé vào cổng tự do đều bị từ chối). */
    @EventListener(ApplicationReadyEvent.class)
    public void warnWhenInternalTokenIsBlank() {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            log.error("[GA_RESERVATION] TICKET_RESERVATION_INTERNAL_TOKEN is blank: every general-admission reservation is refused with 503 until it is set");
        }
    }

    /**
     * Tách đơn thành hai phần. Dòng vé là vé vào cổng tự do khi được đánh dấu {@code generalAdmission} hoặc khi đơn không có ghế;
     * các ghế còn lại đi theo luồng từng ghế.
     * @param request yêu cầu tạo đơn
     * @return kế hoạch gồm các dòng vé tự do và danh sách ghế còn lại
     * @throws IllegalArgumentException khi số lượng của dòng vé tự do không hợp lệ
     */
    public Plan plan(BookingCreateRequest request) {
        List<String> seatIds = request.getSeatIds();
        List<BookingItemRequest> items = request.getItems();
        if (items == null || items.isEmpty()) {
            return new Plan(List.of(), seatIds);
        }

        List<String> remainingSeats = new ArrayList<>(seatIds == null ? List.of() : seatIds);
        Map<String, Integer> ga = new LinkedHashMap<>();

        for (BookingItemRequest item : items) {
            boolean flagged = Boolean.TRUE.equals(item.getGeneralAdmission());
            boolean noSeats = remainingSeats.isEmpty();
            if (flagged || noSeats) {
                ga.merge(item.getTicketTypeId(), requirePositive(item.getQuantity()), Integer::sum);
            }
        }
        List<GaItem> gaItems = ga.entrySet().stream().map(e -> new GaItem(e.getKey(), e.getValue())).toList();
        return new Plan(gaItems, remainingSeats);
    }

    /**
     * Giữ tồn kho; mọi lỗi đều làm đơn bị hoàn tác. Hết vé trả 409 TICKET_SOLD_OUT, các lỗi khác
     * (ticket-service ngừng chạy, sai token) trả 503 để không bán vé mà chưa giữ được hàng. Nếu giao dịch của đơn không commit thì tự nhả lại.
     * @param bookingId mã đơn
     * @param request yêu cầu tạo đơn
     * @param plan kế hoạch tách đơn
     * @param bookingExpiresAt thời điểm đơn hết hạn; cộng thêm {@code slackSeconds} để ra hạn giữ
     * @throws ResponseStatusException HTTP 409 khi hết vé hoặc đơn giữ đã đóng, HTTP 503 khi không giữ được tồn kho
     */
    public void reserve(String bookingId, BookingCreateRequest request, Plan plan, LocalDateTime bookingExpiresAt) {
        Instant holdUntil = bookingExpiresAt.atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(Math.max(0, properties.getSlackSeconds()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("eventId", request.getEventId());
        body.put("showtimeId", request.getShowtimeId());
        body.put("expiresAt", holdUntil.toString());
        body.put("items", plan.gaItems().stream()
                .map(i -> Map.<String, Object>of("ticketTypeId", i.ticketTypeId(), "quantity", i.quantity()))
                .toList());

        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            log.error("[GA_RESERVATION] cannot reserve bookingId={}: internal token is not configured", shortId(bookingId));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_STOCK_UNAVAILABLE");
        }
        try {
            ticketStockClient.reserve(token, body);
        } catch (FeignException.Conflict conflict) {
            String code = codeOf(conflict);
            if ("TICKET_SOLD_OUT".equals(code)) {
                log.info("[GA_RESERVATION] sold out bookingId={}", shortId(bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_SOLD_OUT");
            }
            if ("RESERVATION_CLOSED".equals(code)) {
                log.info("[GA_RESERVATION] reservation already closed bookingId={}", shortId(bookingId));
                throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_CLOSED");
            }
            log.warn("[GA_RESERVATION] reserve refused bookingId={}: {}", shortId(bookingId), conflict.status());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_RESERVATION_REFUSED");
        } catch (RuntimeException e) {
            log.error("[GA_RESERVATION] reserve failed bookingId={}: {}", shortId(bookingId), e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_STOCK_UNAVAILABLE");
        }

        // Nếu giao dịch của đơn này không commit thì trả lại tồn kho ngay; hạn tự hết của lượt giữ
        // ở ticket-service là phương án dự phòng khi lệnh trả này cũng lỗi.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        releaseQuietly(bookingId);
                    }
                }
            });
        }
    }

    /** Trả lại tồn kho đã giữ cho đơn (chỉ khi đơn có cờ {@code gaReserved}). */
    public void releaseIfReserved(Booking booking) {
        if (booking != null && Boolean.TRUE.equals(booking.getGaReserved())) {
            releaseQuietly(booking.getId());
        }
    }

    /**
     * Nhả tồn kho đã giữ cho đơn và không ném lỗi: nếu lỗi thì chỉ ghi log vì lượt giữ sẽ tự hết hạn.
     * @param bookingId mã đơn
     */
    public void releaseQuietly(String bookingId) {
        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            log.error("[GA_RESERVATION] cannot release bookingId={}: internal token is not configured; the hold will expire on its own",
                    shortId(bookingId));
            return;
        }
        try {
            ticketStockClient.release(token, bookingId);
            log.info("[GA_RESERVATION] released bookingId={}", shortId(bookingId));
        } catch (RuntimeException e) {
            log.warn("[GA_RESERVATION] release failed bookingId={} (hold will expire on its own): {}", shortId(bookingId), e.getMessage());
        }
    }

    /**
     * Lấy lại lượt giữ của đơn đã bị hủy hoặc hết hạn trước khi khoản thanh toán đến. Các từ chối không thay đổi gì
     * (hết vé, không mở lại được) là một câu trả lời; lỗi khác (không gọi được, sai token) ném ngoại lệ để bên gọi thử lại.
     * Nếu giao dịch của bên gọi không commit thì tồn kho được trả lại.
     * @param bookingId mã đơn
     * @param expiresAt hạn mới của lượt giữ
     * @return kết quả mở lại
     * @throws IllegalStateException khi không gọi được ticket-service hoặc chưa cấu hình token
     */
    public ReopenOutcome reopen(String bookingId, Instant expiresAt) {
        String token = properties.getInternalToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: internal token is not configured");
        }
        try {
            ticketStockClient.reopen(token, bookingId, Map.of("expiresAt", expiresAt.toString()));
        } catch (FeignException.Conflict conflict) {
            String code = codeOf(conflict);
            if ("TICKET_SOLD_OUT".equals(code) || "RESERVATION_NOT_REOPENABLE".equals(code)) {
                log.info("[GA_RESERVATION] reopen refused bookingId={} code={}", shortId(bookingId), code);
                return new ReopenOutcome(false, code);
            }
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: reopen refused with code " + code, conflict);
        } catch (RuntimeException e) {
            throw new IllegalStateException("TICKET_STOCK_UNAVAILABLE: reopen failed", e);
        }
        // đơn quay lại PAID trong giao dịch của bên gọi; nếu giao dịch không commit thì trả lại tồn kho
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        releaseQuietly(bookingId);
                    }
                }
            });
        }
        log.info("[GA_RESERVATION] reopened bookingId={}", shortId(bookingId));
        return new ReopenOutcome(true, null);
    }

    /** reopened=false nghĩa là ticket-service từ chối với {@code code} và không thay đổi gì. */
    public record ReopenOutcome(boolean reopened, String code) {}

    /** Đọc trường {@code code} trong nội dung lỗi trả về của Feign; chuỗi rỗng nếu không đọc được. */
    private String codeOf(FeignException exception) {
        try {
            JsonNode node = objectMapper.readTree(exception.contentUTF8());
            return node.path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** Kiểm tra số lượng là số dương; ném {@code IllegalArgumentException} (thông báo cho người mua) nếu không. */
    private static int requirePositive(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Số lượng vé không hợp lệ.");
        }
        return quantity;
    }

    /** Rút gọn id còn 8 ký tự đầu để ghi log. */
    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
