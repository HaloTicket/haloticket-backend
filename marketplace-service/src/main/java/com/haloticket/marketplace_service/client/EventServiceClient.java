package com.haloticket.marketplace_service.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
/** Feign client gọi event-service để lấy chính sách bán lại, chính sách tặng vé và quyền của người gọi trên sự kiện. */
@FeignClient(name="event-service", path="/api/events")
public interface EventServiceClient {
    /** Lấy chính sách bán lại vé của sự kiện. */
    @GetMapping("/{eventId}/resale-policy")
    ResalePolicyResponse getResalePolicy(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    /** Lấy chính sách tặng vé của sự kiện. */
    @GetMapping("/{eventId}/gift-policy")
    GiftPolicyResponse getGiftPolicy(@RequestHeader("Authorization") String authorization, @PathVariable("eventId") String eventId);
    /** Lấy vai trò và tập quyền của người gọi trên sự kiện. */
    @GetMapping("/{eventId}/members/my-access")
    com.haloticket.marketplace_service.authz.EventAccessView getMyAccess(@PathVariable("eventId") String eventId, @RequestHeader("Authorization") String authorization);
    /** Chính sách bán lại vé do event-service trả về: khung giá theo phần trăm giá gốc, thời gian mở bán, phí dịch vụ và phạm vi loại vé. */
    record ResalePolicyResponse(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version,
                                BigDecimal serviceFeePercent, boolean requireApproval, Instant opensAt, String ticketTypeScope, java.util.Set<String> allowedTicketTypeIds) {
        /** Constructor rút gọn cho dữ liệu cũ thiếu các trường mới: phí dịch vụ mặc định 5%, không cần duyệt, không giới hạn thời điểm mở và áp dụng cho mọi loại vé. */
        public ResalePolicyResponse(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent, Instant closesAt, String termsVersion, Long version) {
            this(enabled, minPricePercent, maxPricePercent, closesAt, termsVersion, version, new BigDecimal("5"), false, null, "ALL", java.util.Set.of());
        }
    }
    /** Chính sách tặng vé do event-service trả về: thời gian mở, hạn lời mời, yêu cầu eKYC, số lần tặng tối đa và phạm vi loại vé. */
    record GiftPolicyResponse(boolean enabled, Instant opensAt, Instant closesAt, int invitationTtlHours, boolean requireEkyc,
                              int maxGiftCount, String ticketTypeScope, java.util.Set<String> allowedTicketTypeIds, Long version) {}
}
