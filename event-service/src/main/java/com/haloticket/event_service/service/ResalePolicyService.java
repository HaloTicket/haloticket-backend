package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.entity.ResalePolicy;
import com.haloticket.event_service.repository.ResalePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Set;

/** Quản lý chính sách bán lại vé của sự kiện: đọc, tạo hoặc cập nhật, kèm kiểm tra khoảng giá, khóa phiên bản lạc quan và ghi nhật ký kiểm toán. */
@Service @RequiredArgsConstructor
public class ResalePolicyService {
    private final ResalePolicyRepository policyRepository;
    private final EventAccessService access;
    private final TransferPolicySupport support;

    /**
     * Lấy chính sách bán lại vé của sự kiện.
     * @param eventId id sự kiện
     * @return chính sách hiện hành
     * @throws ResponseStatusException 404 {@code POLICY_NOT_CONFIGURED} khi sự kiện chưa cấu hình chính sách
     */
    @Transactional(readOnly = true)
    public ResalePolicyResponse get(String eventId) {
        return policyRepository.findByEventId(eventId).map(ResalePolicyResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_CONFIGURED"));
    }

    /**
     * Tạo mới hoặc cập nhật chính sách bán lại vé. Yêu cầu quyền ghi; giá tối thiểu và tối đa (theo phần trăm giá gốc) bắt buộc, tối đa không nhỏ hơn tối thiểu, phí dịch vụ trong 0..100. Đối chiếu {@code expectedVersion} rồi ghi nhật ký kiểm toán và outbox.
     * @param eventId id sự kiện
     * @param request nội dung chính sách mới
     * @param authorization header Authorization của request
     * @return chính sách sau khi lưu
     * @throws ResponseStatusException 422 {@code INVALID_RESALE_POLICY} khi dữ liệu sai, 409 {@code POLICY_CHANGED} khi phiên bản đã đổi
     */
    @Transactional
    public ResalePolicyResponse upsert(String eventId, ResalePolicyRequest request, String authorization) {
        String actor = access.requireWrite(eventId, authorization);
        if (request == null || request.minPricePercent() == null || request.maxPricePercent() == null
                || request.minPricePercent().signum() < 0
                || request.maxPricePercent().compareTo(request.minPricePercent()) < 0
                || (request.serviceFeePercent() != null && (request.serviceFeePercent().signum() < 0
                    || request.serviceFeePercent().compareTo(new BigDecimal("100")) > 0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESALE_POLICY");
        }
        ResalePolicy existing = policyRepository.findByEventId(eventId).orElse(null);
        if (existing != null) support.version(request.expectedVersion(), existing.getVersion());
        String scope = request.ticketTypeScope() != null ? request.ticketTypeScope() : "ALL";
        Set<String> ids = request.allowedTicketTypeIds() != null ? request.allowedTicketTypeIds() : Set.of();
        support.validate(eventId, request.opensAt(), request.closesAt(), scope, ids);
        ResalePolicy policy = existing != null ? existing : ResalePolicy.builder().eventId(eventId).build();
        Object before = existing != null ? ResalePolicyResponse.from(existing) : null;
        policy.setEnabled(request.enabled());
        policy.setMinPricePercent(request.minPricePercent());
        policy.setMaxPricePercent(request.maxPricePercent());
        policy.setOpensAt(request.opensAt());
        policy.setClosesAt(request.closesAt());
        policy.setTicketTypeScope(scope);
        policy.setAllowedTicketTypeIds(ids);
        policy.setTermsVersion(request.termsVersion());
        if (request.serviceFeePercent() != null) policy.setServiceFeePercent(request.serviceFeePercent());
        if (request.requireApproval() != null) policy.setRequireApproval(request.requireApproval());
        ResalePolicy saved = policyRepository.save(policy);
        support.record(eventId, "RESALE", actor, before, ResalePolicyResponse.from(saved), saved.getVersion());
        return ResalePolicyResponse.from(saved);
    }
}
