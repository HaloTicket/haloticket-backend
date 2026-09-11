package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.EkycDocumentType;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class EkycDecisionPolicyTest {
    @Test void rejectsNonFiniteAndOutOfRangeScores() {
        var policy = new EkycDecisionPolicy(0.9);
        for (double score : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 1.1, -1}) {
            var result = new VnptEkycClient.VerificationResult("ref", EkycDocumentType.ID_CARD,
                    "079123456789", "TEST PERSON", true, true, true, true, score, LocalDate.of(2036, 1, 1));
            assertThat(policy.evaluate(result).passed()).as("score %s", score).isFalse();
        }
    }
    @Test void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EkycDecisionPolicy(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> new EkycDecisionPolicy(0));
    }
    @Test void missingProviderResultCannotApprove() {
        assertThat(new EkycDecisionPolicy(0.9).evaluate(null).passed()).isFalse();
    }
}
