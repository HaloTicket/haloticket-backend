package com.haloticket.user_service.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OtpRateLimitTest {
    @Test
    void enforcesIndependentEmailAndIpHourlyQuotas() {
        OtpRateLimiter limiter = new OtpRateLimiter(2, 2);
        Instant now = Instant.parse("2026-09-04T10:00:00Z");

        assertThat(limiter.tryAcquire("email-a", "ip-a", now)).isTrue();
        assertThat(limiter.tryAcquire("email-a", "ip-b", now)).isTrue();
        assertThat(limiter.tryAcquire("email-a", "ip-c", now)).isFalse();
        assertThat(limiter.tryAcquire("email-b", "ip-a", now)).isTrue();
        assertThat(limiter.tryAcquire("email-c", "ip-a", now)).isFalse();
        assertThat(limiter.tryAcquire("email-b", "ip-b", now.plusSeconds(3601))).isTrue();
    }

    @Test
    void rejectsBlankRateLimitKeys() {
        OtpRateLimiter limiter = new OtpRateLimiter(2, 2);
        assertThat(limiter.tryAcquire("", "ip-a", Instant.now())).isFalse();
        assertThat(limiter.tryAcquire("email-a", "", Instant.now())).isFalse();
    }
}
