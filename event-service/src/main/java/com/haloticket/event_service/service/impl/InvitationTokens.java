package com.haloticket.event_service.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token lời mời dùng một lần: 32 byte ngẫu nhiên gửi cho người được mời, trong cơ sở dữ liệu chỉ lưu mã SHA-256 của chúng. */
final class InvitationTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Lớp tiện ích, không cho tạo đối tượng. */
    private InvitationTokens() {}

    /** Sinh token ngẫu nhiên an toàn (32 byte) mã hóa Base64 URL không đệm. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Tính mã SHA-256 dạng hex của token để lưu và tra cứu.
     * @param token token gốc
     */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
