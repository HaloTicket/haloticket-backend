package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.GiftPolicyRequest;
import com.haloticket.event_service.dto.response.GiftPolicyResponse;
import com.haloticket.event_service.service.GiftPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/events/{eventId}/gift-policy") @RequiredArgsConstructor
public class GiftPolicyController {
    private final GiftPolicyService service;
    @GetMapping public GiftPolicyResponse get(@PathVariable String eventId) { return service.get(eventId); }
    @PutMapping public GiftPolicyResponse put(@PathVariable String eventId, @RequestBody GiftPolicyRequest request,
            @RequestHeader("Authorization") String authorization) { return service.upsert(eventId, request, authorization); }
}
