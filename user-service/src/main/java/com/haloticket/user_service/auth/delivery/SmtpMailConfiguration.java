package com.haloticket.user_service.auth.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
@ConditionalOnProperty(name = "auth.otp.delivery", havingValue = "smtp")
public class SmtpMailConfiguration {
    @Bean
    SmtpOtpDeliveryAdapter smtpOtpDeliveryAdapter(JavaMailSender mailSender, SmtpOtpProperties otpProperties) {
        return new SmtpOtpDeliveryAdapter(mailSender, otpProperties.from(), otpProperties.expiryMinutes());
    }

    @Bean
    JavaMailSender javaMailSender(SmtpOtpProperties properties) {
        validate(true, properties.host(), properties.port(), properties.username(), properties.password(), properties.from());
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setUsername(properties.username());
        sender.setPassword(properties.password());
        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.smtp.auth", properties.smtpAuth());
        javaMailProperties.put("mail.smtp.starttls.enable", properties.startTls());
        return sender;
    }

    @Bean
    SmtpOtpProperties smtpOtpProperties(
            @org.springframework.beans.factory.annotation.Value("${spring.mail.host:}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.port:587}") int port,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}") String username,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.password:}") String password,
            @org.springframework.beans.factory.annotation.Value("${auth.otp.mail.from:}") String from,
            @org.springframework.beans.factory.annotation.Value("${auth.otp.mail.expiry-minutes:5}") int expiryMinutes,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuth,
            @org.springframework.beans.factory.annotation.Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean startTls) {
        return new SmtpOtpProperties(host, port, username, password, from, expiryMinutes, smtpAuth, startTls);
    }

    public static void validate(boolean enabled, String host, int port, String username, String password, String from) {
        if (!enabled) return;
        require(host, "SMTP_HOST");
        if (port < 1 || port > 65535) throw new IllegalStateException("SMTP_PORT must be between 1 and 65535");
        require(username, "SMTP_USERNAME");
        require(password, "SMTP_PASSWORD");
        require(from, "SMTP_FROM");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required when SMTP delivery is enabled");
    }

    public record SmtpOtpProperties(String host, int port, String username, String password, String from,
                                    int expiryMinutes, boolean smtpAuth, boolean startTls) {
        public SmtpOtpProperties {
            if (expiryMinutes < 1) throw new IllegalStateException("OTP expiry must be positive");
        }
    }
}
