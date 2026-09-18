package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycDocumentType;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class EkycDecisionPolicyTest {
    @Test
    void approvesWhenOcrAndLivenessAndFaceMatchAllPass() {
        var policy = new EkycDecisionPolicy();
        var result = new VnptEkycClient.VerificationResult("ref", EkycDocumentType.ID_CARD,
                "079123456789", "TEST PERSON", true, true, true, true, 0.95, LocalDate.of(2036, 1, 1));
        var decision = policy.evaluate(result);
        assertThat(decision.passed()).isTrue();
        assertThat(decision.reasonCode()).isNull();
    }

    @Test
    void rejectsWhenFaceLivenessFails() {
        var policy = new EkycDecisionPolicy();
        var result = new VnptEkycClient.VerificationResult("ref", EkycDocumentType.ID_CARD,
                "079123456789", "TEST PERSON", true, true, false, true, 0.95, LocalDate.of(2036, 1, 1));
        var decision = policy.evaluate(result);
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("FACE_LIVENESS_FAILED");
    }

    @Test
    void rejectsWhenFaceMatchFails() {
        var policy = new EkycDecisionPolicy();
        var result = new VnptEkycClient.VerificationResult("ref", EkycDocumentType.ID_CARD,
                "079123456789", "TEST PERSON", true, true, true, false, 0.45, LocalDate.of(2036, 1, 1));
        var decision = policy.evaluate(result);
        assertThat(decision.passed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("FACE_MATCH_FAILED");
    }

    @Test
    void missingProviderResultCannotApprove() {
        assertThat(new EkycDecisionPolicy().evaluate(null).passed()).isFalse();
    }
}
