package com.haloticket.user_service.service;

/** Các kết quả đầu ra của use case xác thực. */
public final class AuthResponses {
    /** Lớp chứa hằng, không cho tạo đối tượng. */
    private AuthResponses() {}
    /** Bộ token sau khi đăng nhập hoặc làm mới, kèm thời gian sống của access token (giây). */
    public record Login(String accessToken, String refreshToken, String idToken, long expiresIn) {}
    /** Thông tin thử thách OTP trả cho client: mã, hạn dùng và thời điểm được gửi lại. */
    public record Challenge(String challengeId, java.time.Instant expiresAt, java.time.Instant resendAvailableAt) {}
    /** Token đặt lại mật khẩu và thời điểm hết hạn. */
    public record ResetToken(String resetToken, java.time.Instant expiresAt) {}
    /** Nội dung lỗi trả về: mã lỗi, thông điệp và số giây cần chờ (nếu có). */
    public record Error(String code, String message, Integer retryAfterSeconds) {}
}
