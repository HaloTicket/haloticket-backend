package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.entity.ResalePolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** Chính sách bán lại vé của sự kiện trả về cho client, kèm {@code version} để client gửi lại làm {@code expectedVersion} khi sửa. */
public record ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long version, BigDecimal serviceFeePercent, boolean requireApproval) {
    /** Hàm khởi tạo rút gọn: phí dịch vụ mặc định 5% và không yêu cầu duyệt ({@code requireApproval = false}). */
    public ResalePolicyResponse(String eventId, boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
            Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
            String termsVersion, Long version) {
        this(eventId, enabled, minPricePercent, maxPricePercent, opensAt, closesAt, ticketTypeScope,
                allowedTicketTypeIds, termsVersion, version, new BigDecimal("5"), false);
    }

    /**
     * Dựng phản hồi từ thực thể chính sách bán lại.
     * @param policy chính sách đã lưu
     * @return phản hồi tương ứng
     */
    public static ResalePolicyResponse from(ResalePolicy policy) {
        return new ResalePolicyResponse(policy.getEventId(), policy.isEnabled(), policy.getMinPricePercent(),
                policy.getMaxPricePercent(), policy.getOpensAt(), policy.getClosesAt(), policy.getTicketTypeScope(),
                policy.getAllowedTicketTypeIds(), policy.getTermsVersion(), policy.getVersion(),
                policy.getServiceFeePercent(), policy.isRequireApproval());
    }
}
