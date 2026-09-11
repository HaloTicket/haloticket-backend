package com.haloticket.user_service.service;

public class EkycDecisionPolicy {
    public EkycDecisionPolicy() {}

    public Decision evaluate(VnptEkycClient.VerificationResult result) {
        if (result == null || result.documentType() == null || result.providerReference() == null
                || result.providerReference().isBlank()) return Decision.reject("PROVIDER_RESULT_INVALID");
        if (!result.documentValid()) return Decision.reject("DOCUMENT_INVALID");
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
