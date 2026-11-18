package com.haloticket.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** Giữ tồn kho vé phổ thông do ticket-service quản lý. Tách thành client riêng để đặt timeout ngắn riêng. */
@FeignClient(name = "ticket-service", contextId = "ticketStockClient")
public interface TicketStockClient {

    /**
     * Giữ tồn kho vé phổ thông cho một đơn.
     *
     * @param token token nội bộ
     * @param body nội dung yêu cầu giữ chỗ
     */
    @PostMapping("/api/internal/tickets/stock-reservations")
    void reserve(@RequestHeader("X-Internal-Token") String token, @RequestBody Map<String, Object> body);

    /**
     * Nhả phần tồn kho đang giữ của một đơn.
     *
     * @param token token nội bộ
     * @param bookingId mã đơn
     */
    @PostMapping("/api/internal/tickets/stock-reservations/{bookingId}/release")
    void release(@RequestHeader("X-Internal-Token") String token, @PathVariable("bookingId") String bookingId);

    /** Lấy lại phần giữ chỗ đã đóng khi thanh toán đến muộn. Lỗi 409 TICKET_SOLD_OUT / RESERVATION_NOT_REOPENABLE thì không thay đổi gì. */
    @PostMapping("/api/internal/tickets/stock-reservations/{bookingId}/reopen")
    void reopen(@RequestHeader("X-Internal-Token") String token, @PathVariable("bookingId") String bookingId,
                @RequestBody Map<String, Object> body);

    /** Body {"bookingIds":[...tối đa 500]}; trả về {"counts":{"<bookingId>":n}}, mã không có trong kết quả nghĩa là 0. */
    @PostMapping("/api/internal/tickets/issued-counts")
    Map<String, Map<String, Integer>> issuedCounts(@RequestHeader("X-Internal-Token") String token,
                                                   @RequestBody Map<String, Object> body);

    /** Sửa tồn kho còn lại của một loại vé. Lỗi 404 / 409 / 422 thì không thay đổi gì. */
    @PostMapping("/api/internal/tickets/stock-reconciliation/{ticketTypeId}/fix")
    Map<String, Object> fixStock(@RequestHeader("X-Internal-Token") String token, @PathVariable("ticketTypeId") String ticketTypeId);

    /**
     * Lấy các loại vé có tồn kho lệch để đối soát.
     *
     * @param token token nội bộ
     * @param limit số dòng tối đa
     * @return danh sách bản ghi lệch tồn kho
     */
    @GetMapping("/api/internal/tickets/stock-reconciliation")
    List<Map<String, Object>> stockReconciliation(@RequestHeader("X-Internal-Token") String token,
                                                  @RequestParam("limit") int limit);
}
