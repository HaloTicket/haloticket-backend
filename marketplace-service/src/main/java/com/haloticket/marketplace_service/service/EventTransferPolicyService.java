package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.entity.EventTransferPolicyState;
import com.haloticket.marketplace_service.repository.EventTransferPolicyStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;

@Service @RequiredArgsConstructor
public class EventTransferPolicyService {
    private final EventTransferPolicyStateRepository repo;

    public void requireResaleEnabled(String eventId) {
        Optional<EventTransferPolicyState> opt = repo.findById(eventId);
        if (opt.isEmpty()) return; // no gate = default allow (legacy events before policy was configured)
        EventTransferPolicyState s = opt.get();
        if (!s.isResaleEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_DISABLED_BY_EVENT");
        Instant now = Instant.now();
        if (s.getResaleOpensAt() != null && now.isBefore(s.getResaleOpensAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_NOT_STARTED");
        if (s.getResaleClosesAt() != null && now.isAfter(s.getResaleClosesAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_CLOSED");
    }

    public void requireGiftEnabled(String eventId) {
        Optional<EventTransferPolicyState> opt = repo.findById(eventId);
        if (opt.isEmpty()) return; // legacy allow
        EventTransferPolicyState s = opt.get();
        if (!s.isGiftEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_DISABLED_BY_EVENT");
        Instant now = Instant.now();
        if (s.getGiftOpensAt() != null && now.isBefore(s.getGiftOpensAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_NOT_STARTED");
        if (s.getGiftClosesAt() != null && now.isAfter(s.getGiftClosesAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_CLOSED");
    }

    public Optional<EventTransferPolicyState> getState(String eventId) {
        return repo.findById(eventId);
    }
}
