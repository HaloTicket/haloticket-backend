package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.entity.TicketStockReservation;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.StockReservationStatus;
import com.haloticket.ticket_service.repository.TicketStockReservationRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Giữ tồn kho {@code ticket_types} cho các booking vé vào cửa chung chưa thanh toán. Nguồn dữ liệu cuối cùng vẫn là
 * {@code ticket_types.total_quantity}: giữ chỗ chỉ tăng {@code reserved_quantity} và được tiêu thụ trong cùng giao dịch
 * phát hành vé.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockReservationService {

    private final TicketTypeRepository ticketTypes;
    private final TicketStockReservationRepository reservations;

    /** Một dòng cần giữ chỗ: loại vé và số lượng. */
    public record Item(String ticketTypeId, int quantity) {}

    /** Được ném khi một loại vé không đủ số lượng yêu cầu; toàn bộ lượt giữ chỗ bị hoàn tác. */
    public static class SoldOutException extends RuntimeException {
        private final String ticketTypeId;
        private final int available;

        /**
         * @param ticketTypeId mã loại vé không đủ
         * @param available số vé còn có thể giữ
         */
        public SoldOutException(String ticketTypeId, int available) {
            super("TICKET_SOLD_OUT");
            this.ticketTypeId = ticketTypeId;
            this.available = available;
        }

        /** Mã loại vé không đủ số lượng. */
        public String getTicketTypeId() { return ticketTypeId; }

        /** Số vé còn có thể giữ tại thời điểm lỗi. */
        public int getAvailable() { return available; }
    }

    /**
     * Giữ chỗ tất cả hoặc không gì cả, idempotent theo booking. Booking đã có dòng đang giữ thì nhận lại cùng kết quả;
     * booking có dòng đã bị trả hoặc hết hạn thì không đặt lại được dưới cùng mã.
     *
     * @param bookingId mã booking
     * @param eventId mã sự kiện
     * @param showtimeId mã suất diễn (có thể rỗng)
     * @param expiresAt thời điểm hết hạn giữ chỗ, phải ở tương lai
     * @param items các loại vé và số lượng cần giữ
     * @throws ResponseStatusException 400 khi yêu cầu không hợp lệ, 409 khi lượt giữ đã đóng
     * @throws SoldOutException khi một loại vé không đủ tồn kho
     */
    @Transactional
    public void reserve(String bookingId, String eventId, String showtimeId, Instant expiresAt, List<Item> items) {
        if (bookingId == null || bookingId.isBlank() || eventId == null || eventId.isBlank()
                || items == null || items.isEmpty() || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST");
        }

        List<TicketStockReservation> existing = reservations.findByBookingIdForUpdate(bookingId);
        if (!existing.isEmpty()) {
            boolean allHeld = existing.stream().allMatch(r -> r.getStatus() == StockReservationStatus.HELD);
            if (allHeld) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_CLOSED");
        }

        // gộp các dòng trùng và khóa theo thứ tự cố định để các booking đồng thời không bị deadlock.
        Map<String, Integer> wanted = new TreeMap<>();
        for (Item item : items) {
            if (item == null || item.ticketTypeId() == null || item.ticketTypeId().isBlank() || item.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST");
            }
            TicketType type = resolveType(item.ticketTypeId(), showtimeId, eventId);
            wanted.merge(type.getId(), item.quantity(), Integer::sum);
        }

        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            int updated = ticketTypes.reserveAtomic(entry.getKey(), entry.getValue());
            if (updated == 0) {
                throw new SoldOutException(entry.getKey(), availableOf(entry.getKey()));
            }
            reservations.save(TicketStockReservation.builder()
                    .bookingId(bookingId)
                    .ticketTypeId(entry.getKey())
                    .quantity(entry.getValue())
                    .status(StockReservationStatus.HELD)
                    .expiresAt(expiresAt)
                    .build());
        }
        log.info("[GA_RESERVATION] reserved bookingId={} types={}", shortId(bookingId), wanted.size());
    }

    /**
     * Mở lại lượt giữ chỗ đã đóng ({@code RELEASED} hoặc {@code EXPIRED}) cho trường hợp thanh toán đến sau khi lượt giữ đã đóng.
     * Trong một giao dịch: khóa các dòng của booking rồi giữ lại từng dòng đã đóng bằng reserveAtomic; nếu một loại vé không đủ
     * số lượng thì hoàn tác tất cả ({@code SoldOutException}) và không có gì đổi. Idempotent khi mọi dòng đều {@code HELD}.
     * Booking không có dòng nào, hoặc có dòng {@code CONSUMED} (vé đã phát hành), thì không mở lại được.
     *
     * @param bookingId mã booking
     * @param expiresAt thời điểm hết hạn mới, phải ở tương lai
     */
    @Transactional
    public void reopen(String bookingId, Instant expiresAt) {
        if (bookingId == null || bookingId.isBlank() || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST");
        }
        List<TicketStockReservation> rows = reservations.findByBookingIdForUpdate(bookingId);
        if (rows.isEmpty() || rows.stream().anyMatch(r -> r.getStatus() == StockReservationStatus.CONSUMED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_NOT_REOPENABLE");
        }
        for (TicketStockReservation row : rows) {
            if (row.getStatus() == StockReservationStatus.HELD) {
                if (row.getExpiresAt().isBefore(expiresAt)) {
                    row.setExpiresAt(expiresAt);
                    reservations.save(row);
                }
                continue;
            }
            int updated = ticketTypes.reserveAtomic(row.getTicketTypeId(), row.getQuantity());
            if (updated == 0) {
                throw new SoldOutException(row.getTicketTypeId(), availableOf(row.getTicketTypeId()));
            }
            row.setStatus(StockReservationStatus.HELD);
            row.setExpiresAt(expiresAt);
            reservations.save(row);
        }
        log.info("[GA_RESERVATION] reopened bookingId={} rows={}", shortId(bookingId), rows.size());
    }

    /**
     * Trả lại các dòng đang giữ của booking; idempotent.
     *
     * @param bookingId mã booking
     * @return số dòng đang giữ đã được trả
     */
    @Transactional
    public int release(String bookingId) {
        return close(reservations.findByBookingIdForUpdate(bookingId), StockReservationStatus.RELEASED);
    }

    /**
     * Được gọi lúc phát hành vé: tồn kho đang giữ chuyển thành vé phát hành trong cùng giao dịch.
     *
     * @param bookingId mã booking
     * @return số dòng đã chuyển sang {@code CONSUMED}
     */
    @Transactional
    public int consumeForBooking(String bookingId) {
        return close(reservations.findByBookingIdForUpdate(bookingId), StockReservationStatus.CONSUMED);
    }

    /**
     * Chuyển các lượt giữ chỗ đã quá hạn sang {@code EXPIRED} và trả tồn kho.
     *
     * @param limit số dòng tối đa xử lý trong một lô
     * @return số dòng đã xử lý
     */
    @Transactional
    public int expireDue(int limit) {
        int count = close(reservations.findDueForExpiry(Instant.now(), limit), StockReservationStatus.EXPIRED);
        if (count > 0) log.info("[GA_RESERVATION] expired {} stock hold(s)", count);
        return count;
    }

    /**
     * Xác định loại vé theo cùng cách với lúc phát hành vé, để lượt giữ chỗ rơi vào đúng dòng mà phát hành sẽ trừ.
     *
     * @param typeId mã loại vé được yêu cầu
     * @param showtimeId mã suất diễn (có thể rỗng)
     * @param eventId mã sự kiện
     * @return loại vé sẽ được giữ chỗ
     * @throws ResponseStatusException 404 khi không có loại vé, 400 khi sai sự kiện hoặc không có ở suất diễn
     */
    public TicketType resolveType(String typeId, String showtimeId, String eventId) {
        TicketType requested = ticketTypes.findById(typeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_TYPE_NOT_FOUND"));
        if (!eventId.equals(requested.getEventId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TICKET_TYPE_EVENT_MISMATCH");
        }
        if (showtimeId == null || showtimeId.isBlank()) return requested;
        List<TicketType> showtimeTypes = ticketTypes.findByShowtimeId(showtimeId);
        if (showtimeTypes.isEmpty()) return requested;
        return showtimeTypes.stream().filter(t -> t.getId().equals(typeId)).findFirst()
                .or(() -> showtimeTypes.stream()
                        .filter(t -> t.getName() != null && requested.getName() != null
                                && t.getName().trim().equalsIgnoreCase(requested.getName().trim()))
                        .findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "TICKET_TYPE_UNAVAILABLE_FOR_SHOWTIME"));
    }

    /** Đóng các dòng đang {@code HELD} sang trạng thái {@code target}, trả tồn kho về loại vé; trả số dòng đã đóng. */
    private int close(List<TicketStockReservation> rows, StockReservationStatus target) {
        int count = 0;
        for (TicketStockReservation row : rows) {
            if (row.getStatus() != StockReservationStatus.HELD) continue;
            ticketTypes.releaseReservedAtomic(row.getTicketTypeId(), row.getQuantity());
            row.setStatus(target);
            reservations.save(row);
            count++;
        }
        return count;
    }

    /** Số vé còn có thể giữ của loại vé: còn lại trừ đang giữ, không âm. */
    private int availableOf(String typeId) {
        return ticketTypes.findById(typeId)
                .map(t -> Math.max(0, (t.getTotalQuantity() == null ? 0 : t.getTotalQuantity())
                        - (t.getReservedQuantity() == null ? 0 : t.getReservedQuantity())))
                .orElse(0);
    }

    /** Rút gọn mã còn 8 ký tự đầu để ghi log. */
    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
