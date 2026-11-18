package com.haloticket.event_service.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/** Yêu cầu tạo hoặc cập nhật chính sách bán lại vé: biên độ giá theo phần trăm, thời gian mở, phạm vi loại vé và điều khoản. {@code expectedVersion} dùng để phát hiện sửa đồng thời. */
public record ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
        Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
        String termsVersion, Long expectedVersion, BigDecimal serviceFeePercent, Boolean requireApproval) {
    /** Hàm khởi tạo rút gọn cho dạng yêu cầu cũ: không có {@code serviceFeePercent} và {@code requireApproval} (cả hai để {@code null}). */
    public ResalePolicyRequest(boolean enabled, BigDecimal minPricePercent, BigDecimal maxPricePercent,
            Instant opensAt, Instant closesAt, String ticketTypeScope, Set<String> allowedTicketTypeIds,
            String termsVersion, Long expectedVersion) {
        this(enabled, minPricePercent, maxPricePercent, opensAt, closesAt, ticketTypeScope,
                allowedTicketTypeIds, termsVersion, expectedVersion, null, null);
    }
}
