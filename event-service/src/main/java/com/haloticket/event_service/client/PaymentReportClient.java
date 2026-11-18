package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/** Client Feign gọi payment-service để lấy dữ liệu thanh toán phục vụ báo cáo của quản trị viên. */
@FeignClient(name = "payment-service", contextId = "paymentReportClient", path = "/api/payments")
public interface PaymentReportClient {
    /**
     * Lấy danh sách thanh toán đã xác nhận trong khoảng thời gian cho báo cáo quản trị.
     * @param authorization header {@code Authorization} chứa JWT của người gọi, được chuyển tiếp nguyên trạng
     * @param from thời điểm bắt đầu của khoảng cần lấy
     * @param to thời điểm kết thúc của khoảng cần lấy
     * @return các khoản thanh toán cùng số bản ghi thiếu mốc xác nhận
     */
    @GetMapping("/admin/report-payments")
    ReportPaymentsResponse getReportPayments(@RequestHeader("Authorization") String authorization,
            @RequestParam("from") Instant from, @RequestParam("to") Instant to);
}
