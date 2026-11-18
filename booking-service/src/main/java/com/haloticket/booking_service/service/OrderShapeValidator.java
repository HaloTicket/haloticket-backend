package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kiểm tra sớm hình dạng của đơn, trước khi đụng tới Redis, cơ sở dữ liệu hay ticket-service: đơn sẽ lỗi khi phát hành vé
 * sau thanh toán thì bị từ chối ngay ở đây với HTTP 422. Thuần túy, không cần service khác.
 * Mã lỗi: INVALID_ORDER_ITEMS, DUPLICATE_SEAT_IDS, SEAT_COUNT_EXCEEDS_QUANTITY, SEAT_COUNT_MISMATCH.
 */
@Slf4j
@Component
public class OrderShapeValidator {

    /**
     * Kiểm tra dòng vé hợp lệ (có loại vé, số lượng từ 1), ghế không rỗng và không trùng, và số ghế khớp với số vé.
     * @param request yêu cầu tạo đơn, null thì bỏ qua
     * @throws ResponseStatusException HTTP 422 với một trong các mã lỗi ở trên
     */
    public void validate(BookingCreateRequest request) {
        if (request == null) return;
        List<String> seatIds = request.getSeatIds() == null ? List.of() : request.getSeatIds();
        List<BookingItemRequest> items = request.getItems() == null ? List.of() : request.getItems();
        boolean hasSeats = !seatIds.isEmpty();

        if (hasSeats && items.isEmpty()) {
            throw refuse("INVALID_ORDER_ITEMS", "seats without items");
        }

        long totalQuantity = 0;
        boolean anyGeneralAdmission = false;
        for (BookingItemRequest item : items) {
            if (item == null || item.getTicketTypeId() == null || item.getTicketTypeId().isBlank()
                    || item.getQuantity() == null || item.getQuantity() < 1) {
                throw refuse("INVALID_ORDER_ITEMS", "item without ticket type or with quantity below 1");
            }
            totalQuantity += item.getQuantity();
            if (Boolean.TRUE.equals(item.getGeneralAdmission())) anyGeneralAdmission = true;
        }

        if (hasSeats) {
            Set<String> seen = new HashSet<>();
            for (String seatId : seatIds) {
                if (seatId == null || seatId.isBlank() || !seen.add(seatId)) {
                    throw refuse("DUPLICATE_SEAT_IDS", "blank or repeated seat id");
                }
            }
            if (seatIds.size() > totalQuantity) {
                throw refuse("SEAT_COUNT_EXCEEDS_QUANTITY", "seats=" + seatIds.size() + " quantity=" + totalQuantity);
            }
            // đơn chỉ có ghế thì mỗi ghế một vé; đơn hỗn hợp không kiểm tra chặt hơn được vì mã ghế
            // không cho biết nó thuộc loại vé nào
            if (!anyGeneralAdmission && seatIds.size() != totalQuantity) {
                throw refuse("SEAT_COUNT_MISMATCH", "seats=" + seatIds.size() + " quantity=" + totalQuantity);
            }
        }
    }

    /** Ghi log và tạo ngoại lệ HTTP 422 với mã lỗi đã cho. */
    private static ResponseStatusException refuse(String code, String detail) {
        log.warn("[ORDER_SHAPE] refused {}: {}", code, detail);
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }
}
