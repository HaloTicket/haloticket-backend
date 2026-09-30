package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.TransferSettingsResponse;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.service.EventAccessService;
import com.haloticket.event_service.service.GiftPolicyService;
import com.haloticket.event_service.service.ResalePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/events/{eventId}/transfer-settings") @RequiredArgsConstructor
public class TransferSettingsController {
    private final ResalePolicyService resaleService;
    private final GiftPolicyService giftService;
    private final EventAccessService access;

    @GetMapping
    public TransferSettingsResponse get(@PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        StaffRole role = access.requireRead(eventId, authorization);
        var resale = tryGet(() -> resaleService.get(eventId));
        var gift = tryGet(() -> giftService.get(eventId));
        boolean canWrite = role == StaffRole.OWNER || role == StaffRole.ADMIN;
        return new TransferSettingsResponse(resale, gift, canWrite);
    }

    private <T> T tryGet(java.util.function.Supplier<T> s) {
        try { return s.get(); } catch (ResponseStatusException ignored) { return null; }
    }
}
