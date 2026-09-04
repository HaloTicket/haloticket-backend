package com.haloticket.user_service.auth;

import java.security.SecureRandom;

public class OtpGenerator {
    private static final int OTP_BOUND = 1_000_000;
    private final SecureRandom secureRandom;

    public OtpGenerator() {
        this(new SecureRandom());
    }

    OtpGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        return "%06d".formatted(secureRandom.nextInt(OTP_BOUND));
    }
}
