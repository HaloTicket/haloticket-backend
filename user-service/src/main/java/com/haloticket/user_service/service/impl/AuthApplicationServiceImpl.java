package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.service.AuthApplicationService;
import com.haloticket.user_service.service.AuthOtpChallengeService;
import com.haloticket.user_service.service.AuthRequests;
import com.haloticket.user_service.service.AuthResponses;
import com.haloticket.user_service.service.KeycloakAuthPort;
import com.haloticket.user_service.service.OtpDeliveryPort;
import com.haloticket.user_service.service.OtpChallengeCommand;
import com.haloticket.user_service.service.OtpRateLimiter;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** Cài đặt các use case xác thực: điều phối OTP, giới hạn tần suất, gửi mã và gọi Keycloak. */
@Service
public class AuthApplicationServiceImpl implements AuthApplicationService {
    private final AuthOtpChallengeService otpService;
    private final OtpRateLimiter rateLimiter;
    private final OtpDeliveryPort delivery;
    private final KeycloakAuthPort keycloak;
    private final Clock clock;

    /** Tạo service với các thành phần phụ thuộc. */
    public AuthApplicationServiceImpl(AuthOtpChallengeService otpService,
                                      OtpRateLimiter rateLimiter,
                                      OtpDeliveryPort delivery,
                                      KeycloakAuthPort keycloak,
                                      Clock clock) {
        this.otpService = otpService;
        this.rateLimiter = rateLimiter;
        this.delivery = delivery;
        this.keycloak = keycloak;
        this.clock = clock;
    }

    /**
     * Kiểm tra mật khẩu và email, bảo đảm email chưa đăng ký, áp dụng giới hạn tần suất rồi tạo thử thách OTP và gửi mã.
     *
     * @param request dữ liệu bắt đầu đăng ký
     * @return thử thách vừa tạo
     * @throws AuthException khi dữ liệu sai, email đã tồn tại hoặc vượt giới hạn gửi
     */
    @Override
    public AuthOtpChallengeService.CreateResult startRegistration(AuthRequests.RegisterStart request) {
        requirePassword(request.password());
        requireEmail(request.email());
        keycloak.ensureRegistrationAvailable(request.email());
        acquire(request.email(), request.requestIpHash());
        var result = otpService.create(new OtpChallengeCommand(
                OtpPurpose.REGISTRATION, request.email(), request.idempotencyKey(), request.requestIpHash()));
        deliver(result, request.email(), OtpPurpose.REGISTRATION);
        return result;
    }

    /**
     * Xác minh OTP rồi tạo tài khoản Keycloak và đánh dấu thử thách đã dùng.
     *
     * @param request mã thử thách, email, mật khẩu và OTP
     * @throws AuthException HTTP 410 khi OTP sai hoặc hết hạn, HTTP 400 khi mật khẩu không đạt
     */
    @Override
    public void verifyRegistration(AuthRequests.RegisterVerify request) {
        requirePassword(request.password());
        var result = otpService.verify(request.challengeId(), request.otp(), clock.instant());
        if (!result.accepted()) {
            throw new AuthException("OTP_INVALID", "OTP is invalid or expired", 410);
        }
        keycloak.register(request.email(), request.password());
        otpService.consume(request.challengeId(), clock.instant());
    }

    /** Sinh lại OTP cho thử thách đăng ký và gửi tới email của thử thách. */
    @Override
    public AuthOtpChallengeService.CreateResult resendRegistration(AuthRequests.Challenge request) {
        var result = otpService.resend(request.challengeId(), clock.instant());
        deliver(result, result.challenge().getSubject(), OtpPurpose.REGISTRATION);
        return result;
    }

    /**
     * Tìm email của tài khoản theo định danh (email hoặc số điện thoại), áp dụng giới hạn tần suất rồi tạo thử thách OTP và gửi mã.
     *
     * @param request định danh, khóa idempotency và mã băm IP
     * @return thử thách vừa tạo
     * @throws AuthException khi thiếu định danh, không tìm thấy tài khoản hoặc vượt giới hạn gửi
     */
    @Override
    public AuthOtpChallengeService.CreateResult startPasswordReset(AuthRequests.PasswordForgot request) {
        if (request.identifier() == null || request.identifier().isBlank())
            throw new AuthException("INVALID_REQUEST", "Email or phone number is required", 400);
        String email = keycloak.resolvePasswordResetEmail(request.identifier())
                .orElseThrow(() -> new AuthException("RESET_NOT_AVAILABLE", "Unable to start password recovery", 400));
        acquire(email, request.requestIpHash());
        var result = otpService.create(new OtpChallengeCommand(
                OtpPurpose.PASSWORD_RESET, email, request.idempotencyKey(), request.requestIpHash()));
        deliver(result, email, OtpPurpose.PASSWORD_RESET);
        return result;
    }

    /**
     * Xác minh OTP đặt lại mật khẩu và cấp reset token.
     *
     * @param request mã thử thách và OTP
     * @return reset token
     * @throws AuthException HTTP 410 khi OTP sai hoặc hết hạn
     */
    @Override
    public String verifyPasswordReset(AuthRequests.OtpVerify request) {
        var result = otpService.verify(request.challengeId(), request.otp(), clock.instant());
        if (!result.accepted()) {
            throw new AuthException("OTP_INVALID", "OTP is invalid or expired", 410);
        }
        return keycloak.issueResetToken(request.challengeId());
    }

    /** Sinh lại OTP cho thử thách đặt lại mật khẩu và gửi tới email của thử thách. */
    @Override
    public AuthOtpChallengeService.CreateResult resendPasswordReset(AuthRequests.Challenge request) {
        var result = otpService.resend(request.challengeId(), clock.instant());
        deliver(result, result.challenge().getSubject(), OtpPurpose.PASSWORD_RESET);
        return result;
    }

    /** Kiểm tra chính sách mật khẩu rồi đặt mật khẩu mới bằng reset token. */
    @Override
    public void resetPassword(AuthRequests.PasswordReset request) {
        requirePassword(request.password());
        keycloak.resetPassword(request.resetToken(), request.password());
    }

    /** Đăng nhập qua Keycloak bằng email và mật khẩu. */
    @Override
    public AuthResponses.Login login(AuthRequests.Login request) {
        return keycloak.login(request.email(), request.password());
    }

    /** Làm mới token qua Keycloak; báo HTTP 400 nếu thiếu refresh token. */
    @Override
    public AuthResponses.Login refresh(AuthRequests.Refresh request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new AuthException("INVALID_REFRESH_TOKEN", "Refresh token is required", 400);
        }
        return keycloak.refresh(request.refreshToken(), request.clientId());
    }

    /** Gửi OTP tới email nếu kết quả có mã chữ rõ (thử thách cũ được trả lại do trùng idempotency thì không có mã, nên không gửi lại). */
    private void deliver(AuthOtpChallengeService.CreateResult result, String email, OtpPurpose purpose) {
        if (result.plaintextOtp() != null) {
            delivery.send(email, purpose, result.plaintextOtp(), result.challenge().getExpiresAt());
        }
    }

    /** Xin một lượt gửi OTP, báo HTTP 429 {@code OTP_RATE_LIMITED} nếu vượt giới hạn. */
    private void acquire(String subject, String ipHash) {
        if (!rateLimiter.tryAcquire(subject, ipHash, clock.instant())) {
            throw new AuthException("OTP_RATE_LIMITED", "Too many OTP requests", 429);
        }
    }

    /** Kiểm tra email đúng định dạng cơ bản, nếu không báo HTTP 400 {@code EMAIL_INVALID}. */
    private static void requireEmail(String email) {
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new AuthException("EMAIL_INVALID", "Email is invalid", 400);
        }
    }

    /** Kiểm tra mật khẩu dài 8 đến 32 ký tự, có chữ thường, chữ hoa, chữ số và ký tự đặc biệt; nếu không báo HTTP 400 {@code PASSWORD_INVALID}. */
    private static void requirePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 32
                || !password.matches(".*[a-z].*")
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*\\d.*")
                || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new AuthException("PASSWORD_INVALID", "Password does not meet policy", 400);
        }
    }

    /** Biến thể lỗi xác thực dùng nội bộ trong lớp này. */
    private static class AuthException extends AuthApplicationService.AuthException {
        /** Tạo lỗi với mã lỗi, thông điệp và mã HTTP. */
        private AuthException(String code, String message, int status) {
            super(code, message, status);
        }
    }
}
