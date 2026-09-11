package com.haloticket.user_service.controller;

import com.haloticket.user_service.service.EkycDtos;
import com.haloticket.user_service.service.EkycService;
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

    @GetMapping
    public ResponseEntity<EkycDtos.StatusResponse> status(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ekycService.getStatus(jwt.getSubject()));
    }

    @PostMapping("/sessions")
    public ResponseEntity<EkycDtos.SessionResponse> startSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody EkycDtos.StartSessionRequest request
    ) {
        return ResponseEntity.ok(ekycService.startSession(
                jwt.getSubject(), request.consentAccepted(), request.consentVersion(), idempotencyKey
        ));
    }

    @PostMapping(
            value = "/sessions/{sessionId}/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<EkycDtos.StatusResponse> verify(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId,
            @RequestPart MultipartFile frontImage,
            @RequestPart MultipartFile backImage,
            @RequestPart MultipartFile faceImage
    ) {
        return ResponseEntity.ok(ekycService.verify(
                jwt.getSubject(), sessionId, frontImage, backImage, faceImage
        ));
    }

    @GetMapping("/organizer-eligibility")
    public ResponseEntity<EkycDtos.OrganizerEligibility> organizerEligibility(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ekycService.getOrganizerEligibility(jwt.getSubject()));
    }
}
