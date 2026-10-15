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

@Service @RequiredArgsConstructor
public class GiftPolicyService {
    private final GiftPolicyRepository repository;
    private final EventAccessService access;
    private final TransferPolicySupport support;

    @Transactional(readOnly = true)
    public GiftPolicyResponse get(String eventId) {
        return repository.findByEventId(eventId).map(GiftPolicyResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_CONFIGURED"));
    }

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
