package com.haloticket.user_service.service;

import org.springframework.web.multipart.MultipartFile;

public interface EkycService {
    EkycDtos.SessionResponse startSession(
            String keycloakId,
            boolean consentAccepted,
            String consentVersion,
            String idempotencyKey
    );

    EkycDtos.StatusResponse getStatus(String keycloakId);

    EkycDtos.StatusResponse verify(
            String keycloakId,
            String sessionId,
            MultipartFile frontImage,
            MultipartFile backImage,
            MultipartFile faceImage
    );

    EkycDtos.OrganizerEligibility getOrganizerEligibility(String keycloakId);
}
