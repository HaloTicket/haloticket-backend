package com.haloticket.user_service.service;

import java.security.SecureRandom;

/** Sinh mã OTP 6 chữ số bằng nguồn ngẫu nhiên an toàn. */
public class OtpGenerator {
    private static final int OTP_BOUND = 1_000_000;
    private final SecureRandom secureRandom;

    /** Tạo bộ sinh dùng {@link SecureRandom} mặc định. */
    public OtpGenerator() {
        this(new SecureRandom());
    }

    /** Tạo bộ sinh với nguồn ngẫu nhiên cho trước (dành cho kiểm thử). */
    OtpGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /** Sinh một mã OTP gồm đúng 6 chữ số, thêm số 0 ở đầu nếu cần. */
    public String generate() {
        return "%06d".formatted(secureRandom.nextInt(OTP_BOUND));
    }
}
