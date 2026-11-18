package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.AdminEventReportResponse;
import com.haloticket.event_service.service.AdminEventReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Map;

/** Điểm vào API báo cáo sự kiện dành cho quản trị viên: xem theo trang và xuất toàn bộ. */
@RestController
@RequestMapping("/api/events/admin/reports")
@RequiredArgsConstructor
public class AdminEventReportController {
    private final AdminEventReportService reportService;

    /**
     * Lấy báo cáo sự kiện theo khoảng ngày, có lọc, sắp xếp và phân trang.
     * @param authorization header {@code Authorization} của người gọi, chuyển tiếp khi gọi các service khác
     * @param from ngày bắt đầu
     * @param to ngày kết thúc
     * @param q từ khóa tìm theo sự kiện
     * @param categoryId lọc theo danh mục
     * @param organizerId lọc theo nhà tổ chức
     * @param status lọc theo trạng thái sự kiện
     * @param sort tiêu chí sắp xếp, mặc định {@code revenue}
     * @param page số trang, bắt đầu từ 1
     * @param size số dòng mỗi trang
     * @param eventId giới hạn vào một sự kiện
     * @return báo cáo của trang được chọn
     */
    @GetMapping
    public ResponseEntity<AdminEventReportResponse> report(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String organizerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "revenue") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String eventId) {
        return ResponseEntity.ok(reportService.report(authorization, from, to, q, categoryId, organizerId, status,
                sort, page, size, eventId, false));
    }

    /**
     * Lấy dữ liệu báo cáo để xuất file: cùng bộ lọc như {@code report} nhưng trả toàn bộ dòng trong một trang.
     * @param authorization header {@code Authorization} của người gọi
     * @param from ngày bắt đầu
     * @param to ngày kết thúc
     * @param q từ khóa tìm theo sự kiện
     * @param categoryId lọc theo danh mục
     * @param organizerId lọc theo nhà tổ chức
     * @param status lọc theo trạng thái sự kiện
     * @param sort tiêu chí sắp xếp, mặc định {@code revenue}
     * @param eventId giới hạn vào một sự kiện
     * @return báo cáo đầy đủ để xuất
     */
    @GetMapping("/export")
    public ResponseEntity<AdminEventReportResponse> export(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String organizerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "revenue") String sort,
            @RequestParam(required = false) String eventId) {
        return ResponseEntity.ok(reportService.report(authorization, from, to, q, categoryId, organizerId, status,
                sort, 1, 10, eventId, true));
    }

    /**
     * Đổi {@code ResponseStatusException} thành phản hồi JSON {@code {message}} giữ nguyên mã trạng thái; thiếu lý do thì dùng câu thông báo mặc định.
     * @param exception ngoại lệ do tầng báo cáo ném ra
     * @return phản hồi chứa thông báo lỗi
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> reportError(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "Yêu cầu báo cáo không hợp lệ." : exception.getReason()));
    }
}
