package com.haloticket.user_service.service;

public class EkycDecisionPolicy {
    private final double minimumFaceMatchScore;

    public EkycDecisionPolicy(double minimumFaceMatchScore) {
        if (!Double.isFinite(minimumFaceMatchScore) || minimumFaceMatchScore <= 0 || minimumFaceMatchScore > 1) {
            throw new IllegalArgumentException("Face match threshold must be in (0, 1]");
        }
        this.minimumFaceMatchScore = minimumFaceMatchScore;
    }

    public Decision evaluate(VnptEkycClient.VerificationResult result) {
        if (result == null || result.documentType() == null || result.providerReference() == null
                || result.providerReference().isBlank()) return Decision.reject("PROVIDER_RESULT_INVALID");
        if (!Double.isFinite(result.matchScore()) || result.matchScore() < 0 || result.matchScore() > 1) {
            return Decision.reject("FACE_MATCH_FAILED");
        }
        if (!result.documentValid()) return Decision.reject("DOCUMENT_INVALID");
        if (!result.documentLivenessPassed()) return Decision.reject("DOCUMENT_LIVENESS_FAILED");
        if (!result.faceLivenessPassed()) return Decision.reject("FACE_LIVENESS_FAILED");
        if (!result.faceMatched() || result.matchScore() < minimumFaceMatchScore) {
            return Decision.reject("FACE_MATCH_FAILED");
        }
        if (result.documentNumber() == null || result.documentNumber().isBlank()
                || result.legalName() == null || result.legalName().isBlank()) {
            return Decision.reject("OCR_DATA_INCOMPLETE");
        }
        return Decision.approve();
    }

    public record Decision(boolean passed, String reasonCode) {
        static Decision approve() { return new Decision(true, null); }
        static Decision reject(String reason) { return new Decision(false, reason); }
    }
}
