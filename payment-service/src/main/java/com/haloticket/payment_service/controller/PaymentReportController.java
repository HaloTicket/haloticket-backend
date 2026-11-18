package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** API báo cáo thanh toán dành cho admin: liệt kê các thanh toán thành công trong một khoảng thời gian. */
@RestController
@RequestMapping("/api/payments/admin")
@RequiredArgsConstructor
public class PaymentReportController {
    private static final int MAX_ROWS = 100_000;
    private final OutboxEventRepository outboxEventRepository;

    @Value("${reports.source-zone:}")
    private String sourceZone;

    /**
     * Trả các thanh toán thành công có thời điểm xác nhận trong khoảng [from, to). Khoảng tối đa 732 ngày, tối đa 100000 dòng;
     * thời điểm lưu theo múi giờ nguồn {@code reports.source-zone} (mặc định múi giờ hệ thống).
     *
     * @param from thời điểm bắt đầu
     * @param to thời điểm kết thúc, phải sau {@code from}
     * @return danh sách thanh toán và số đơn thiếu thời điểm xác nhận
     * @throws ResponseStatusException 400 khi khoảng không hợp lệ, 422 khi vượt giới hạn dòng, 500 khi múi giờ cấu hình sai
     */
    @GetMapping("/report-payments")
    public PaymentReportResponse reportPayments(@RequestParam Instant from, @RequestParam Instant to) {
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(732)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng thời gian báo cáo không hợp lệ; tối đa 732 ngày.");
        }
        ZoneId zone;
        try {
            zone = sourceZone == null || sourceZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(sourceZone);
        } catch (RuntimeException invalidZone) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cấu hình múi giờ báo cáo không hợp lệ.");
        }
        LocalDateTime localFrom = LocalDateTime.ofInstant(from, zone);
        LocalDateTime localTo = LocalDateTime.ofInstant(to, zone);
        List<OutboxEventRepository.SuccessfulPaymentFact> rows = outboxEventRepository
                .findSuccessfulPaymentFactsInRange(localFrom, localTo, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dữ liệu báo cáo vượt quá giới hạn 100000 kết quả. Vui lòng thu hẹp khoảng thời gian.");
        }
        List<PaymentReportItem> payments = rows.stream()
                .map(row -> new PaymentReportItem(row.getBookingId(), row.getPaymentId(),
                        row.getConfirmedAt().atZone(zone).toInstant(),
                        row.getRevenue() == null ? null : BigDecimal.valueOf(row.getRevenue())))
                .toList();
        return new PaymentReportResponse(payments, outboxEventRepository.countBookingsMissingConfirmationTimestamp());
    }

    /** Một dòng báo cáo: đơn, thanh toán, thời điểm xác nhận và doanh thu. */
    public record PaymentReportItem(String bookingId, String paymentId, Instant confirmedAt, BigDecimal revenue) {}
    /** Kết quả báo cáo: danh sách thanh toán và số đơn chưa có thời điểm xác nhận. */
    public record PaymentReportResponse(List<PaymentReportItem> payments, long missingTimestampCount) {}
}
