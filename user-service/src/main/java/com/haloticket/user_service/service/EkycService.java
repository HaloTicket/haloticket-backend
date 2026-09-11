package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.response.EkycOrganizerEligibilityResponse;
import com.haloticket.user_service.dto.response.EkycSessionResponse;
import com.haloticket.user_service.dto.response.EkycStatusResponse;
import org.springframework.web.multipart.MultipartFile;

public interface EkycService {
    EkycSessionResponse startSession(
            String keycloakId,
            boolean consentAccepted,
            String consentVersion,
            String idempotencyKey
    );

    EkycStatusResponse getStatus(String keycloakId);

    EkycStatusResponse verify(
            String keycloakId,
            String sessionId,
            MultipartFile frontImage,
            MultipartFile backImage
    );

    EkycOrganizerEligibilityResponse getOrganizerEligibility(String keycloakId);

}
