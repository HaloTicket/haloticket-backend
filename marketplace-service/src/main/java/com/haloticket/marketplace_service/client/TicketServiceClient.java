package com.haloticket.marketplace_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Feign client gọi ticket-service để kiểm tra điều kiện bán lại của một vé. */
@FeignClient(name = "ticket-service", path = "/api/tickets")
public interface TicketServiceClient {
    /** Kiểm tra vé có được đăng bán lại không và lấy thông tin vé cùng khung giá cho phép. */
    @GetMapping("/{ticketId}/resale-eligibility")
    ResaleEligibilityResponse getResaleEligibility(@RequestHeader("Authorization") String authorization, @PathVariable String ticketId);

    /** Kết quả kiểm tra điều kiện bán lại: vé có đủ điều kiện không (kèm mã lý do nếu không), giá gốc, khung giá, phiên bản chính sách, thông tin ghế và số lần vé đã được tặng. */
    record ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                     BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                     Long policyVersion, Instant closesAt, String termsVersion,
                                     String ticketTypeName, String section, String row, String seat,
                                     String ticketTypeId, String showtimeId, int giftTransferCount, BigDecimal serviceFeePercent) {
        /** Constructor cho dữ liệu cũ có thông tin loại vé và suất diễn nhưng thiếu số lần tặng và phí dịch vụ: số lần tặng mặc định 0, phí dịch vụ mặc định 5%. */
        public ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                         BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                         Long policyVersion, Instant closesAt, String termsVersion, String ticketTypeName,
                                         String section, String row, String seat, String ticketTypeId, String showtimeId) {
            this(ticketId, eventId, eligible, reasonCode, originalPrice, minPrice, maxPrice, policyVersion, closesAt,
                    termsVersion, ticketTypeName, section, row, seat, ticketTypeId, showtimeId, 0, new BigDecimal("5"));
        }
        /** Constructor cho dữ liệu cũ chỉ có thông tin ghế: loại vé và suất diễn để null, số lần tặng mặc định 0, phí dịch vụ mặc định 5%. */
        public ResaleEligibilityResponse(String ticketId, String eventId, boolean eligible, String reasonCode,
                                         BigDecimal originalPrice, BigDecimal minPrice, BigDecimal maxPrice,
                                         Long policyVersion, Instant closesAt, String termsVersion,
                                         String ticketTypeName, String section, String row, String seat) {
            this(ticketId, eventId, eligible, reasonCode, originalPrice, minPrice, maxPrice, policyVersion,
                    closesAt, termsVersion, ticketTypeName, section, row, seat, null, null, 0, new BigDecimal("5"));
        }
    }
}
