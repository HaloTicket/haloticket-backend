package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.service.ResalePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/events/{eventId}/resale-policy") @RequiredArgsConstructor
public class ResalePolicyController {
    private final ResalePolicyService service;
    @GetMapping public ResalePolicyResponse get(@PathVariable String eventId) { return service.get(eventId); }
    @PutMapping public ResalePolicyResponse put(@PathVariable String eventId, @RequestBody ResalePolicyRequest request) { return service.upsert(eventId, request); }
}
