package com.haloticket.user_service.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class OtpHasher {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] pepper;

    public OtpHasher(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("OTP pepper must be configured");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String otp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(otp.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash OTP", exception);
        }
    }

    public boolean matches(String otp, String expectedHash) {
        return MessageDigest.isEqual(
                hash(otp).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
