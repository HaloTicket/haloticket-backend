package com.haloticket.event_service.dto.response;
import com.haloticket.event_service.entity.GiftPolicy;
import java.time.Instant;
import java.util.Set;
/** Chính sách tặng vé của sự kiện trả về cho client, kèm {@code version} để client gửi lại làm {@code expectedVersion} khi sửa. */
public record GiftPolicyResponse(String eventId, boolean enabled, Instant opensAt, Instant closesAt,
        int invitationTtlHours, String ticketTypeScope, Set<String> allowedTicketTypeIds, Long version,
        boolean requireEkyc, int maxGiftCount) {
    /** Hàm khởi tạo rút gọn: mặc định yêu cầu eKYC ({@code requireEkyc = true}) và tối đa 3 lượt tặng ({@code maxGiftCount = 3}). */
    public GiftPolicyResponse(String eventId, boolean enabled, Instant opensAt, Instant closesAt,
            int invitationTtlHours, String ticketTypeScope, Set<String> allowedTicketTypeIds, Long version) {
        this(eventId, enabled, opensAt, closesAt, invitationTtlHours, ticketTypeScope, allowedTicketTypeIds,
                version, true, 3);
    }

    /**
     * Dựng phản hồi từ thực thể chính sách tặng vé.
     * @param p chính sách đã lưu
     * @return phản hồi tương ứng
     */
    public static GiftPolicyResponse from(GiftPolicy p) {
        return new GiftPolicyResponse(p.getEventId(), p.isEnabled(), p.getOpensAt(), p.getClosesAt(),
                p.getInvitationTtlHours(), p.getTicketTypeScope(), p.getAllowedTicketTypeIds(), p.getVersion(),
                p.isRequireEkyc(), p.getMaxGiftCount());
    }
}
