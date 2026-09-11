package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.EkycStartSessionRequest;
import com.haloticket.user_service.dto.response.EkycOrganizerEligibilityResponse;
import com.haloticket.user_service.dto.response.EkycSessionResponse;
import com.haloticket.user_service.dto.response.EkycStatusResponse;
import com.haloticket.user_service.service.EkycService;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users/me/ekyc")
@RequiredArgsConstructor
public class EkycController {
    private final EkycService ekycService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<EkycStatusResponse> status(@AuthenticationPrincipal Jwt jwt) {
        if (jwt != null) {
            try {
                userService.syncCurrentUser(jwt);
            } catch (Exception ignored) {
                // Ignore sync errors on read to avoid blocking status check
            }
        }
        return ResponseEntity.ok(ekycService.getStatus(jwt != null ? jwt.getSubject() : null));
    }

    @PostMapping("/sessions")
    public ResponseEntity<EkycSessionResponse> startSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EkycStartSessionRequest request
    ) {
        if (jwt != null) {
            userService.syncCurrentUser(jwt);
        }
        return ResponseEntity.ok(ekycService.startSession(
                jwt != null ? jwt.getSubject() : null, request.consentAccepted(), request.consentVersion(), idempotencyKey
        ));
    }

    @PostMapping(
            value = "/sessions/{sessionId}/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<EkycStatusResponse> verify(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestPart MultipartFile frontImage,
            @RequestPart MultipartFile backImage,
            @RequestPart(required = false) MultipartFile faceImage
    ) {
        return ResponseEntity.ok(ekycService.verify(
                jwt.getSubject(), sessionId, frontImage, backImage, faceImage
        ));
    }

    @GetMapping("/organizer-eligibility")
    public ResponseEntity<EkycOrganizerEligibilityResponse> organizerEligibility(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ekycService.getOrganizerEligibility(jwt.getSubject()));
    }
}
