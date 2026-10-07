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

    // organizer endpoints
    @GetMapping("/organizer")
    public ResponseEntity<List<PayoutResponse>> getOrganizerPayouts(@RequestHeader("Authorization") String authHeader) {
        String organizerId = extractUserId(authHeader);
        return ResponseEntity.ok(payoutService.getOrganizerPayouts(organizerId));
    }

    @GetMapping("/organizer/eligible-events")
    public ResponseEntity<List<java.util.Map<String, Object>>> getEligibleEvents(@RequestHeader("Authorization") String authHeader) {
        // mock eligible events for payout
        return ResponseEntity.ok(List.of(
            java.util.Map.of(
                "eventId", "EVT-123",
                "eventName", "The Eras Tour - Hanoi",
                "totalRevenue", 500000000,
                "platformFeeRate", 0.05,
                "netAmount", 475000000,
                "image", "https://images.unsplash.com/photo-1540039155733-5bb30b53aa14"
            ),
            java.util.Map.of(
                "eventId", "EVT-456",
                "eventName", "Rap Viet All-Star",
                "totalRevenue", 120000000,
                "platformFeeRate", 0.05,
                "netAmount", 114000000,
                "image", "https://images.unsplash.com/photo-1470229722913-7c090be103f6"
            ),
            java.util.Map.of(
                "eventId", "EVT-789",
                "eventName", "Anh Trai Say Hi - HCM",
                "totalRevenue", 800000000,
                "platformFeeRate", 0.05,
                "netAmount", 760000000,
                "image", "https://images.unsplash.com/photo-1492684223066-81342ee5ff30"
            ),
            java.util.Map.of(
                "eventId", "EVT-101",
                "eventName", "Anh Trai Vượt Ngàn Chông Gai",
                "totalRevenue", 650000000,
                "platformFeeRate", 0.05,
                "netAmount", 617500000,
                "image", "https://images.unsplash.com/photo-1514525253161-7a46d19cd819"
            ),
            java.util.Map.of(
                "eventId", "EVT-202",
                "eventName", "Blackpink World Tour",
                "totalRevenue", 1500000000,
                "platformFeeRate", 0.05,
                "netAmount", 1425000000,
                "image", "https://images.unsplash.com/photo-1459749411175-04bf5292ceea"
            )
        ));
    }

    @PostMapping("/organizer/request")
    public ResponseEntity<PayoutResponse> requestPayout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PayoutCreateRequest request) {
        String organizerId = extractUserId(authHeader);
        return ResponseEntity.ok(payoutService.requestPayout(organizerId, request));
    }

    private String extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return "mock-organizer-id";
        try {
            String[] parts = authHeader.split("\\.");
            if (parts.length > 1) {
                String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                return node.has("sub") ? node.get("sub").asText() : "mock-organizer-id";
            }
        } catch (Exception e) {}
        return "mock-organizer-id";
    }
}
