package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.entity.ResalePolicy;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.ResalePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

@Service @RequiredArgsConstructor
public class ResalePolicyService {
    private final ResalePolicyRepository policyRepository;
    private final EventRepository eventRepository;

    @Transactional(readOnly = true)
    public ResalePolicyResponse get(String eventId) {
        return policyRepository.findByEventId(eventId).map(ResalePolicyResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "POLICY_NOT_CONFIGURED"));
    }

    @Transactional
    public ResalePolicyResponse upsert(String eventId, ResalePolicyRequest request) {
        if (!eventRepository.existsById(eventId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        if (request == null || request.minPricePercent() == null || request.maxPricePercent() == null || request.minPricePercent().signum() < 0 || request.maxPricePercent().compareTo(request.minPricePercent()) < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESALE_POLICY");
        }
        ResalePolicy policy = policyRepository.findByEventId(eventId).orElseGet(() -> ResalePolicy.builder().eventId(eventId).build());
        policy.setEnabled(request.enabled()); policy.setMinPricePercent(request.minPricePercent()); policy.setMaxPricePercent(request.maxPricePercent()); policy.setClosesAt(request.closesAt()); policy.setTermsVersion(request.termsVersion());
        return ResalePolicyResponse.from(policyRepository.save(policy));
    }
}
