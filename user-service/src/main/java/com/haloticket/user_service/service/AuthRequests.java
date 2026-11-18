package com.haloticket.user_service.service;

/** Các lệnh đầu vào của use case xác thực, tách khỏi DTO của tầng web. */
public final class AuthRequests {
    /** Lớp chứa hằng, không cho tạo đối tượng. */
    private AuthRequests() {}
    /** Bắt đầu đăng ký: email, mật khẩu, khóa idempotency và mã băm IP của người gọi. */
    public record RegisterStart(String email, String password, String idempotencyKey, String requestIpHash) {}
    /** Xác minh đăng ký: mã thử thách, email, mật khẩu và OTP. */
    public record RegisterVerify(String challengeId, String email, String password, String otp) {}
    /** Tham chiếu một thử thách OTP (kèm email nếu có) cho thao tác gửi lại. */
    public record Challenge(String challengeId, String email) {}
    /** Bắt đầu quên mật khẩu: định danh tài khoản, khóa idempotency và mã băm IP. */
    public record PasswordForgot(String identifier, String idempotencyKey, String requestIpHash) {}
    /** Xác minh OTP: mã thử thách và OTP người dùng nhập. */
    public record OtpVerify(String challengeId, String otp) {}
    /** Đặt mật khẩu mới bằng reset token. */
    public record PasswordReset(String resetToken, String password) {}
    /** Đăng nhập bằng email và mật khẩu. */
    public record Login(String email, String password) {}
    /** Làm mới token bằng refresh token và client id. */
    public record Refresh(String refreshToken, String clientId) {}
}
