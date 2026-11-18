package com.haloticket.user_service.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Băm OTP bằng HMAC-SHA256 với khóa bí mật (pepper), để không lưu OTP dạng chữ rõ. */
public class OtpHasher {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] pepper;

    /**
     * Tạo bộ băm với khóa bí mật.
     *
     * @param pepper khóa bí mật, không được rỗng
     * @throws IllegalArgumentException khi khóa rỗng
     */
    public OtpHasher(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("OTP pepper must be configured");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Tính mã băm hex của OTP.
     *
     * @param otp mã OTP
     * @return mã băm dạng hex
     * @throws IllegalStateException khi không băm được
     */
    public String hash(String otp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(otp.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash OTP", exception);
        }
    }

    /** So khớp OTP với mã băm đã lưu bằng phép so sánh thời gian hằng số để tránh lộ thông tin qua thời gian xử lý. */
    public boolean matches(String otp, String expectedHash) {
        return MessageDigest.isEqual(
                hash(otp).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
