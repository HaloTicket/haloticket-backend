package com.haloticket.user_service.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VnptEkycHttpClientTest {
    @Test
    void ocrRequestDeclaresCitizenCardWithChip() {
        var request = VnptEkycHttpClient.ocrRequest("front", "back", "session", "token");

        assertThat(request.get("type")).isEqualTo(9);
    }
}
