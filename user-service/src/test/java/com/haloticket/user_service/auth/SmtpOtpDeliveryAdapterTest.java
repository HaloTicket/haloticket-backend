package com.haloticket.user_service.auth;

import com.haloticket.user_service.auth.domain.OtpPurpose;
import com.haloticket.user_service.auth.delivery.SmtpOtpDeliveryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpOtpDeliveryAdapterTest {
    @Test
    void sendsVerificationEmailWithPurposeCodeAndExpiry() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpOtpDeliveryAdapter adapter = new SmtpOtpDeliveryAdapter(sender, "no-reply@haloticket.vn", 5);
        Instant expiresAt = Instant.parse("2026-09-04T10:05:00Z");

        adapter.send("user@example.com", OtpPurpose.REGISTRATION, "123456", expiresAt);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify((MailSender) sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("no-reply@haloticket.vn");
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).contains("HaloTicket");
        assertThat(message.getText()).contains("123456", "5 phút");
    }
}
