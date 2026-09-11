package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycDocumentType;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class EkycDecisionPolicyTest {
    @Test void approvesValidOcrWithoutLivenessOrFaceChecks() {
        var policy = new EkycDecisionPolicy();
        var result = new VnptEkycClient.VerificationResult("ref", EkycDocumentType.ID_CARD,
                "079123456789", "TEST PERSON", true, false, false, false, Double.NaN, LocalDate.of(2036, 1, 1));
        assertThat(policy.evaluate(result).passed()).isTrue();
    }
    @Test void missingProviderResultCannotApprove() {
        assertThat(new EkycDecisionPolicy().evaluate(null).passed()).isFalse();
    }
}
