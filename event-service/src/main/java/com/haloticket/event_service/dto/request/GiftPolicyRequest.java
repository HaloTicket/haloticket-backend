package com.haloticket.event_service.dto.request;
import java.time.Instant;
import java.util.Set;
/** Yêu cầu tạo hoặc cập nhật chính sách tặng vé của sự kiện. {@code expectedVersion} dùng để phát hiện sửa đồng thời; {@code requireEkyc} và {@code maxGiftCount} là tùy chọn. */
public record GiftPolicyRequest(boolean enabled, Instant opensAt, Instant closesAt, Integer invitationTtlHours,
        String ticketTypeScope, Set<String> allowedTicketTypeIds, Long expectedVersion,
        Boolean requireEkyc, Integer maxGiftCount) {
    /** Hàm khởi tạo rút gọn cho dạng yêu cầu cũ: không có {@code requireEkyc} và {@code maxGiftCount} (cả hai để {@code null}). */
    public GiftPolicyRequest(boolean enabled, Instant opensAt, Instant closesAt, Integer invitationTtlHours,
            String ticketTypeScope, Set<String> allowedTicketTypeIds, Long expectedVersion) {
        this(enabled, opensAt, closesAt, invitationTtlHours, ticketTypeScope, allowedTicketTypeIds,
                expectedVersion, null, null);
    }
}
