package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.GiftPolicyRequest;
import com.haloticket.event_service.dto.response.GiftPolicyResponse;
import com.haloticket.event_service.entity.GiftPolicy;
import com.haloticket.event_service.repository.GiftPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

/** Quản lý chính sách tặng vé của sự kiện: đọc, tạo hoặc cập nhật, kèm kiểm tra hợp lệ, khóa phiên bản lạc quan và ghi nhật ký kiểm toán. */
@Service @RequiredArgsConstructor
public class GiftPolicyService {
    private final GiftPolicyRepository repository;
    private final EventAccessService access;
    private final TransferPolicySupport support;

    /**
     * Lấy chính sách tặng vé của sự kiện.
     * @param eventId id sự kiện
     * @return chính sách hiện hành
     * @throws ResponseStatusException 404 {@code POLICY_NOT_CONFIGURED} khi sự kiện chưa cấu hình chính sách
     */
    @Transactional(readOnly = true)
    public GiftPolicyResponse get(String eventId) {
        return repository.findByEventId(eventId).map(GiftPolicyResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_CONFIGURED"));
    }

    /**
     * Tạo mới hoặc cập nhật chính sách tặng vé. Yêu cầu quyền ghi, kiểm tra {@code maxGiftCount} trong 0..100 và thời hạn lời mời 1..168 giờ (mặc định 24), đối chiếu {@code expectedVersion} với phiên bản hiện có, rồi ghi nhật ký kiểm toán và outbox.
     * @param eventId id sự kiện
     * @param request nội dung chính sách mới
     * @param authorization header Authorization của request
     * @return chính sách sau khi lưu
     * @throws ResponseStatusException 422 {@code INVALID_POLICY} khi dữ liệu sai, 409 {@code POLICY_CHANGED} khi phiên bản đã đổi
     */
    @Transactional
    public GiftPolicyResponse upsert(String eventId, GiftPolicyRequest request, String authorization) {
        String actor = access.requireWrite(eventId, authorization);
        if (request == null || (request.maxGiftCount() != null
                && (request.maxGiftCount() < 0 || request.maxGiftCount() > 100))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POLICY");
        }
        GiftPolicy existing = repository.findByEventId(eventId).orElse(null);
        if (existing != null) support.version(request.expectedVersion(), existing.getVersion());
        String scope = request.ticketTypeScope() != null ? request.ticketTypeScope() : "ALL";
        Set<String> ids = request.allowedTicketTypeIds() != null ? request.allowedTicketTypeIds() : Set.of();
        support.validate(eventId, request.opensAt(), request.closesAt(), scope, ids);
        int ttl = request.invitationTtlHours() != null ? request.invitationTtlHours() : 24;
        if (ttl < 1 || ttl > 168) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_POLICY");
        GiftPolicy policy = existing != null ? existing : GiftPolicy.builder().eventId(eventId).build();
        Object before = existing != null ? GiftPolicyResponse.from(existing) : null;
        policy.setEnabled(request.enabled());
        policy.setOpensAt(request.opensAt());
        policy.setClosesAt(request.closesAt());
        policy.setInvitationTtlHours(ttl);
        policy.setTicketTypeScope(scope);
        policy.setAllowedTicketTypeIds(ids);
        if (request.requireEkyc() != null) policy.setRequireEkyc(request.requireEkyc());
        if (request.maxGiftCount() != null) policy.setMaxGiftCount(request.maxGiftCount());
        GiftPolicy saved = repository.save(policy);
        support.record(eventId, "GIFT", actor, before, GiftPolicyResponse.from(saved), saved.getVersion());
        return GiftPolicyResponse.from(saved);
    }
}
