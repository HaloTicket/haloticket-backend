package com.haloticket.user_service.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Tạo dấu vân tay (HMAC-SHA256) từ số giấy tờ để phát hiện một giấy tờ bị dùng cho nhiều tài khoản mà không lưu số gốc. */
public class IdentityFingerprint {
    private final byte[] secret;

    /** Tạo bộ băm với khóa bí mật; khóa rỗng vẫn được tạo nhưng {@link #requireConfigured()} sẽ báo chưa cấu hình. */
    public IdentityFingerprint(String secret) {
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Bảo đảm khóa bí mật đủ dài (ít nhất 16 byte).
     *
     * @throws ResponseStatusException HTTP 503 {@code EKYC_NOT_CONFIGURED} khi khóa thiếu hoặc quá ngắn
     */
    public void requireConfigured() {
        if (secret.length < 16) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_NOT_CONFIGURED");
        }
    }

    /**
     * Tính mã băm HMAC-SHA256 của số giấy tờ sau khi bỏ ký tự không phải chữ hoặc số và đổi sang chữ hoa.
     *
     * @param documentNumber số giấy tờ
     * @return mã băm dạng hex
     * @throws ResponseStatusException HTTP 503 khi chưa cấu hình khóa
     */
    public String create(String documentNumber) {
        requireConfigured();
        String normalized = documentNumber == null ? "" : documentNumber.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot create document fingerprint", exception);
        }
    }
}
