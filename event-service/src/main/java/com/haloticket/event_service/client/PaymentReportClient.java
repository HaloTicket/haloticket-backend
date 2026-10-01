package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

@FeignClient(name = "payment-service", contextId = "paymentReportClient", path = "/api/payments")
public interface PaymentReportClient {
    @GetMapping("/admin/report-payments")
    ReportPaymentsResponse getReportPayments(@RequestHeader("Authorization") String authorization,
            @RequestParam("from") Instant from, @RequestParam("to") Instant to);
}
