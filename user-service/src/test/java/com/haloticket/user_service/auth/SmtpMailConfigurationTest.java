package com.haloticket.user_service.auth;

import com.haloticket.user_service.auth.delivery.SmtpMailConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpMailConfigurationTest {
    @Test
    void rejectsMissingRequiredSmtpSettingsWhenEnabled() {
        assertThatThrownBy(() -> SmtpMailConfiguration.validate(true, "", 0, "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP_HOST");
    }
}
