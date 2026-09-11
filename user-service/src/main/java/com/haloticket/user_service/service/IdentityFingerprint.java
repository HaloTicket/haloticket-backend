package com.haloticket.user_service.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class IdentityFingerprint {
    private final byte[] secret;

    public IdentityFingerprint(String secret) {
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    public void requireConfigured() {
        if (secret.length < 16) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_NOT_CONFIGURED");
        }
    }

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
