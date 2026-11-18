package com.haloticket.user_service.service;

import com.haloticket.user_service.enums.OtpPurpose;

/** Lệnh tạo thử thách OTP: mục đích, đối tượng nhận, khóa idempotency và mã băm IP của người gọi. */
public record OtpChallengeCommand(
        OtpPurpose purpose,
        String subject,
        String idempotencyKey,
        String requestIpHash
) {
    /**
     * Chuẩn hóa và kiểm tra dữ liệu đầu vào.
     *
     * @throws IllegalArgumentException khi thiếu mục đích, đối tượng hoặc khóa idempotency, hoặc giá trị quá dài
     */
    public OtpChallengeCommand {
        if (purpose == null) throw new IllegalArgumentException("OTP purpose is required");
        subject = normalize(subject, 320, "OTP subject");
        idempotencyKey = normalize(idempotencyKey, 128, "OTP idempotency key");
        requestIpHash = requestIpHash == null ? null : requestIpHash.trim();
    }

    /** Cắt khoảng trắng và bảo đảm giá trị không rỗng, không dài quá giới hạn; ngược lại ném {@code IllegalArgumentException}. */
    private static String normalize(String value, int max, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }
}
