package com.haloticket.payment_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutPolicyControllerTest {
    @Test
    void legacyAdvancePolicyEndpointIsRetired() {
        assertThatThrownBy(() -> new PayoutPolicyController().retiredAdvancePolicy())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410 GONE");
    }
}
