package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

/**
 * API quản trị đối soát đơn hàng (chỉ ROLE_ADMIN). Các dòng dữ liệu chứa tên, email và số điện thoại
 * khách để nhân viên liên hệ, tuyệt đối không ghi log chúng.
 */
@RestController
@RequestMapping("/api/bookings/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private static final int MAX_PAGE_SIZE = 100;

    /** Nội dung yêu cầu xử lý một ngoại lệ thanh toán: ghi chú và cờ đã hoàn tiền thủ công. */
    public record ResolveRequest(String note, Boolean refundedManually) {}

    private final ReconciliationService reconciliationService;

    /** Trả số liệu tổng hợp của các ngoại lệ thanh toán. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return reconciliationService.summary();
    }

    /**
     * Liệt kê ngoại lệ thanh toán có phân trang, lọc tùy chọn theo trạng thái.
     * @param status trạng thái cần lọc (không phân biệt hoa thường), bỏ trống để lấy tất cả
     * @param page số trang bắt đầu từ 0
     * @param size số dòng mỗi trang, từ 1 đến 100
     * @return trang kết quả
     * @throws ResponseStatusException HTTP 422 khi trạng thái hoặc tham số phân trang không hợp lệ
     */
    @GetMapping("/exceptions")
    public ReconciliationService.ExceptionPage exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PaymentExceptionStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = PaymentExceptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS");
            }
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE");
        }
        return reconciliationService.exceptions(filter, page, size);
    }

    /**
     * Đánh dấu một ngoại lệ thanh toán đã được xử lý, kèm ghi chú và việc hoàn tiền thủ công.
     * Quản trị viên được lấy từ subject của JWT, không có thì ghi {@code unknown}.
     * @param id mã ngoại lệ
     * @param body ghi chú và cờ {@code refundedManually}
     * @param jwt token của quản trị viên
     * @return ngoại lệ sau khi cập nhật
     */
    @PostMapping("/exceptions/{id}/resolve")
    public ReconciliationService.ExceptionItem resolve(@PathVariable String id, @RequestBody ResolveRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String admin = jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank() ? jwt.getSubject() : "unknown";
        return reconciliationService.resolve(id, body == null ? null : body.note(),
                body != null && Boolean.TRUE.equals(body.refundedManually()), admin);
    }

    /** Liệt kê các đơn đã thanh toán nhưng chưa được phát hành vé. */
    @GetMapping("/paid-without-tickets")
    public ReconciliationService.PaidWithoutTickets paidWithoutTickets() {
        return reconciliationService.paidWithoutTickets();
    }

    /** Đồng bộ lại kho cho một loại vé: số còn lại = số ban đầu trừ số đã phát hành. Các từ chối (404/409/422) được chuyển tiếp và không thay đổi dữ liệu. */
    @PostMapping("/stock/{ticketTypeId}/fix")
    public Map<String, Object> fixStock(@PathVariable String ticketTypeId) {
        return reconciliationService.fixStock(ticketTypeId);
    }

    /** Liệt kê các loại vé có số tồn kho lệch so với số vé đã phát hành. */
    @GetMapping("/stock-discrepancies")
    public ReconciliationService.StockDiscrepancies stockDiscrepancies() {
        return reconciliationService.stockDiscrepancies();
    }
}
