package com.haloticket.user_service.service;

/** Các use case xác thực của người dùng: đăng ký và đặt lại mật khẩu qua OTP, đăng nhập và làm mới token. */
public interface AuthApplicationService {
    /** Bắt đầu đăng ký: tạo thử thách OTP và gửi mã tới email. */
    AuthOtpChallengeService.CreateResult startRegistration(AuthRequests.RegisterStart request);
    /** Xác minh OTP đăng ký rồi tạo tài khoản. */
    void verifyRegistration(AuthRequests.RegisterVerify request);
    /** Gửi lại OTP đăng ký cho một thử thách đã có. */
    AuthOtpChallengeService.CreateResult resendRegistration(AuthRequests.Challenge request);
    /** Bắt đầu quên mật khẩu: tạo thử thách OTP gửi tới email của tài khoản. */
    AuthOtpChallengeService.CreateResult startPasswordReset(AuthRequests.PasswordForgot request);
    /** Xác minh OTP đặt lại mật khẩu; trả về reset token dùng cho bước đặt mật khẩu mới. */
    String verifyPasswordReset(AuthRequests.OtpVerify request);
    /** Gửi lại OTP đặt lại mật khẩu cho một thử thách đã có. */
    AuthOtpChallengeService.CreateResult resendPasswordReset(AuthRequests.Challenge request);
    /** Đặt mật khẩu mới bằng reset token đã nhận sau khi xác minh OTP. */
    void resetPassword(AuthRequests.PasswordReset request);
    /** Đăng nhập bằng email và mật khẩu, trả về các token. */
    AuthResponses.Login login(AuthRequests.Login request);
    /** Đổi refresh token lấy bộ token mới. */
    AuthResponses.Login refresh(AuthRequests.Refresh request);

    /** Lỗi nghiệp vụ xác thực, mang mã lỗi ổn định và mã HTTP để controller trả về cho client. */
    class AuthException extends RuntimeException {
        private final String code;
        private final int status;

        /** Tạo lỗi với mã lỗi, thông điệp và mã HTTP. */
        public AuthException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        /** Trả về mã lỗi ổn định. */
        public String code() { return code; }
        /** Trả về mã HTTP tương ứng. */
        public int status() { return status; }
    }
}
