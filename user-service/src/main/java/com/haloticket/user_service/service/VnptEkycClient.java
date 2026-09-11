package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycDocumentType;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

public interface VnptEkycClient {
    void requireReady();
    VerificationResult verify(MultipartFile frontImage, MultipartFile backImage);

    record VerificationResult(
            String providerReference,
            EkycDocumentType documentType,
            String documentNumber,
            String legalName,
            boolean documentValid,
            boolean documentLivenessPassed,
            boolean faceLivenessPassed,
            boolean faceMatched,
            double matchScore,
            LocalDate documentExpiresOn,
            String birthDay,
            String address
    ) {
        public VerificationResult(
                String providerReference,
                EkycDocumentType documentType,
                String documentNumber,
                String legalName,
                boolean documentValid,
                boolean documentLivenessPassed,
                boolean faceLivenessPassed,
                boolean faceMatched,
                double matchScore,
                LocalDate documentExpiresOn
        ) {
            this(providerReference, documentType, documentNumber, legalName, documentValid,
                    documentLivenessPassed, faceLivenessPassed, faceMatched, matchScore, documentExpiresOn, null, null);
        }
    }
}
