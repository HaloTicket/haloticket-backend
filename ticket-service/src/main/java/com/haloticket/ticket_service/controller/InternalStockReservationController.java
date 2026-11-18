package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.service.StockReservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chỉ dùng giữa các dịch vụ: gateway vẫn có thể định tuyến tới đây nên mọi lời gọi phải mang token dùng chung;
 * chưa cấu hình token thì từ chối tất cả. Dùng để giữ chỗ, nhả chỗ và mở lại giữ chỗ tồn kho cho booking.
 */
@RestController
@RequestMapping("/api/internal/tickets/stock-reservations")
public class InternalStockReservationController {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final StockReservationService stockReservations;
    private final String serviceToken;

    /** Khởi tạo với dịch vụ giữ chỗ tồn kho và token dịch vụ ({@code ticket.stock-reservation.internal-token}). */
    public InternalStockReservationController(StockReservationService stockReservations,
            @Value("${ticket.stock-reservation.internal-token:}") String serviceToken) {
        this.stockReservations = stockReservations;
        this.serviceToken = serviceToken;
    }

    /** Yêu cầu giữ chỗ: booking, sự kiện, suất diễn, thời điểm hết hạn và danh sách loại vé kèm số lượng. */
    public record ReserveRequest(String bookingId, String eventId, String showtimeId, Instant expiresAt, List<ReserveItem> items) {}

    /** Một dòng giữ chỗ: loại vé và số lượng. */
    public record ReserveItem(String ticketTypeId, Integer quantity) {}

    /** Giữ chỗ tồn kho cho booking; hết vé thì trả 409 kèm mã {@code TICKET_SOLD_OUT}, loại vé và số còn lại. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody ReserveRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        List<StockReservationService.Item> items = request.items() == null ? List.of()
                : request.items().stream()
                        .map(i -> new StockReservationService.Item(i == null ? null : i.ticketTypeId(),
                                i == null || i.quantity() == null ? 0 : i.quantity()))
                        .toList();
        try {
            stockReservations.reserve(request.bookingId(), request.eventId(), request.showtimeId(), request.expiresAt(), items);
        } catch (StockReservationService.SoldOutException soldOut) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "TICKET_SOLD_OUT");
            body.put("ticketTypeId", soldOut.getTicketTypeId());
            body.put("available", soldOut.getAvailable());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return ResponseEntity.ok(Map.of("reserved", true));
    }

    /** Nhả toàn bộ chỗ đang giữ của booking; trả về số dòng giữ chỗ đã nhả (idempotent). */
    @PostMapping("/{bookingId}/release")
    public Map<String, Object> release(@PathVariable String bookingId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        return Map.of("released", stockReservations.release(bookingId));
    }

    /** Yêu cầu mở lại giữ chỗ với thời điểm hết hạn mới. */
    public record ReopenRequest(Instant expiresAt) {}

    /** Mở lại giữ chỗ cho booking (thanh toán muộn); trả 409 khi hết vé hoặc giữ chỗ không thể mở lại. */
    @PostMapping("/{bookingId}/reopen")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable String bookingId, @RequestBody ReopenRequest request,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        try {
            stockReservations.reopen(bookingId, request == null ? null : request.expiresAt());
        } catch (StockReservationService.SoldOutException soldOut) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "TICKET_SOLD_OUT");
            body.put("ticketTypeId", soldOut.getTicketTypeId());
            body.put("available", soldOut.getAvailable());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (ResponseStatusException notReopenable) {
            if (notReopenable.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", notReopenable.getReason());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            throw notReopenable;
        }
        return ResponseEntity.ok(Map.of("reopened", true));
    }

    /** Kiểm tra token dịch vụ bằng so sánh thời gian không đổi; sai, thiếu hoặc chưa cấu hình thì ném 403. */
    private void requireToken(String token) {
        if (serviceToken.isBlank() || token == null || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
