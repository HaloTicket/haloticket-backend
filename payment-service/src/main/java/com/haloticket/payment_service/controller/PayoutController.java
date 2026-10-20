package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    // admin endpoints
    @GetMapping("/admin/pending")
    public ResponseEntity<List<PayoutResponse>> getPendingPayouts() {
        return ResponseEntity.ok(payoutService.getPendingPayouts());
    }

    @GetMapping("/admin/all")
    public ResponseEntity<List<PayoutResponse>> getAllAdminPayouts() {
        return ResponseEntity.ok(payoutService.getAllAdminPayouts());
    }

    @PostMapping("/admin/{id}/approve")
    public ResponseEntity<PayoutResponse> approvePayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.approvePayout(id));
    }

    @PostMapping("/admin/{id}/dispute")
    public ResponseEntity<PayoutResponse> disputePayout(
            @PathVariable String id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(payoutService.disputePayout(id, reason));
    }

    @PostMapping("/admin/{id}/reopen")
    public ResponseEntity<PayoutResponse> reopenPayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.reopenPayout(id));
    }

    @PostMapping("/admin/{id}/reject")
    public ResponseEntity<PayoutResponse> rejectPayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.rejectPayout(id));
    }

    // organizer endpoints
    @GetMapping("/organizer")
    public ResponseEntity<List<PayoutResponse>> getOrganizerPayouts(@RequestHeader("Authorization") String authHeader) {
        String organizerId = extractUserId(authHeader);
        return ResponseEntity.ok(payoutService.getOrganizerPayouts(organizerId));
    }

    @GetMapping("/organizer/eligible-events")
    public ResponseEntity<List<java.util.Map<String, Object>>> getEligibleEvents(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(List.of());
    }

    @PostMapping("/organizer/request")
    public ResponseEntity<PayoutResponse> requestPayout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PayoutCreateRequest request) {
        String organizerId = extractUserId(authHeader);
        return ResponseEntity.ok(payoutService.requestPayout(organizerId, request));
    }

    private String extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing or invalid authorization token");
        }
        try {
            String token = authHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length > 1) {
                String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                if (node.has("sub") && !node.get("sub").asText().isBlank()) {
                    return node.get("sub").asText();
                }
            }
        } catch (Exception ignored) {}
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid authorization token");
    }
}
