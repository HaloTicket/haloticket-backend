package com.haloticket.user_service.controller;

import com.haloticket.user_service.service.AuthApplicationService;
import com.haloticket.user_service.service.AuthRequests;
import com.haloticket.user_service.service.AuthResponses;
import com.haloticket.user_service.service.AuthOtpChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** API xác thực công khai ({@code /api/auth}): đăng ký và quên mật khẩu qua OTP email, đăng nhập và làm mới token. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthApplicationService service;

    /** @param service dịch vụ nghiệp vụ xác thực */
    public AuthController(AuthApplicationService service) { this.service = service; }

    /** Bắt đầu đăng ký: kiểm tra email, mật khẩu, khóa idempotency rồi gửi OTP; trả thử thách OTP với mã 202. */
    @PostMapping("/register/start")
    public ResponseEntity<AuthResponses.Challenge> registerStart(@RequestBody RegisterStartRequest request,
                                                                  HttpServletRequest http) {
        validateEmail(request.email());
        validatePassword(request.password());
        requireText(request.idempotencyKey(), "idempotencyKey");
        var result = service.startRegistration(new AuthRequests.RegisterStart(request.email(), request.password(),
                request.idempotencyKey(), ipHash(http)));
        return ResponseEntity.accepted().body(challenge(result));
    }

    /** Xác nhận OTP đăng ký và tạo tài khoản; trả 204. */
    @PostMapping("/register/verify")
    public ResponseEntity<Void> registerVerify(@RequestBody RegisterVerifyRequest request) {
        service.verifyRegistration(new AuthRequests.RegisterVerify(request.challengeId(), request.email(), request.password(), request.otp()));
        return ResponseEntity.noContent().build();
    }

    /** Gửi lại OTP đăng ký cho thử thách đang có; trả thử thách mới với mã 202. */
    @PostMapping("/register/resend")
    public ResponseEntity<AuthResponses.Challenge> registerResend(@RequestBody ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(service.resendRegistration(new AuthRequests.Challenge(request.challengeId(), null))));
    }

    /** Bắt đầu quên mật khẩu theo định danh (email hoặc tên đăng nhập) và gửi OTP; trả thử thách với mã 202. */
    @PostMapping("/password/forgot")
    public ResponseEntity<AuthResponses.Challenge> passwordForgot(@RequestBody PasswordForgotRequest request,
                                                                   HttpServletRequest http) {
        requireText(request.identifier(), "identifier");
        requireText(request.idempotencyKey(), "idempotencyKey");
        var result = service.startPasswordReset(new AuthRequests.PasswordForgot(request.identifier(),
                request.idempotencyKey(), ipHash(http)));
        return ResponseEntity.accepted().body(challenge(result));
    }

    /** Xác nhận OTP quên mật khẩu và trả token đặt lại mật khẩu, hạn dùng ghi là 600 giây kể từ lúc trả lời. */
    @PostMapping("/password/verify")
    public ResponseEntity<AuthResponses.ResetToken> passwordVerify(@RequestBody OtpVerifyRequest request) {
        return ResponseEntity.ok(new AuthResponses.ResetToken(service.verifyPasswordReset(
                new AuthRequests.OtpVerify(request.challengeId(), request.otp())), Instant.now().plusSeconds(600)));
    }

    /** Gửi lại OTP quên mật khẩu cho thử thách đang có; trả thử thách mới với mã 202. */
    @PostMapping("/password/resend")
    public ResponseEntity<AuthResponses.Challenge> passwordResend(@RequestBody ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(service.resendPasswordReset(new AuthRequests.Challenge(request.challengeId(), null))));
    }

    /** Đặt mật khẩu mới bằng token đặt lại; mật khẩu phải đạt chính sách; trả 204. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> passwordReset(@RequestBody PasswordResetRequest request) {
        requireText(request.resetToken(), "resetToken");
        validatePassword(request.password());
        service.resetPassword(new AuthRequests.PasswordReset(request.resetToken(), request.password()));
        return ResponseEntity.noContent().build();
    }

    /** Đăng nhập bằng email và mật khẩu, trả token đăng nhập. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponses.Login> login(@RequestBody LoginRequest request) {
        validateEmail(request.email());
        requireText(request.password(), "password");
        return ResponseEntity.ok(service.login(new AuthRequests.Login(request.email(), request.password())));
    }

    /** Làm mới token từ refresh token. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponses.Login> refresh(@RequestBody RefreshRequest request) {
        requireText(request.refreshToken(), "refreshToken");
        return ResponseEntity.ok(service.refresh(new AuthRequests.Refresh(request.refreshToken(), request.clientId())));
    }

    /** Đổi kết quả tạo thử thách OTP thành phản hồi gồm mã thử thách, hạn dùng và thời điểm được gửi lại. */
    private AuthResponses.Challenge challenge(AuthOtpChallengeService.CreateResult result) {
        return new AuthResponses.Challenge(result.challenge().getId(), result.challenge().getExpiresAt(), result.challenge().getResendAvailableAt());
    }

    /** Lấy mã băm IP do gateway gắn trong header {@code X-Request-IP-Hash}; thiếu thì dùng {@code anonymous}. */
    private String ipHash(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-IP-Hash");
        return supplied == null || supplied.isBlank() ? "anonymous" : supplied.trim();
    }

    /**
     * Kiểm tra định dạng email.
     *
     * @throws AuthApplicationService.AuthException 400 {@code EMAIL_INVALID} khi sai định dạng
     */
    private void validateEmail(String value) {
        if (value == null || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new AuthApplicationService.AuthException("EMAIL_INVALID", "Email is invalid", 400);
    }

    /**
     * Kiểm tra mật khẩu dài 8 đến 32 ký tự, có chữ thường, chữ hoa, chữ số và ký tự đặc biệt.
     *
     * @throws AuthApplicationService.AuthException 400 {@code PASSWORD_INVALID} khi không đạt
     */
    private void validatePassword(String value) {
        if (value == null || value.length() < 8 || value.length() > 32 || !value.matches(".*[a-z].*")
                || !value.matches(".*[A-Z].*") || !value.matches(".*\\d.*") || !value.matches(".*[^A-Za-z0-9].*"))
            throw new AuthApplicationService.AuthException("PASSWORD_INVALID", "Password does not meet policy", 400);
    }

    /**
     * Bắt buộc trường văn bản không trống.
     *
     * @throws AuthApplicationService.AuthException 400 {@code INVALID_REQUEST} khi thiếu
     */
    private void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new AuthApplicationService.AuthException("INVALID_REQUEST", field + " is required", 400);
    }

    /** Nội dung yêu cầu bắt đầu đăng ký: email, mật khẩu và khóa idempotency. */
    public record RegisterStartRequest(String email, String password, String idempotencyKey) {}
    /** Nội dung yêu cầu xác nhận đăng ký: mã thử thách, email, mật khẩu và mã OTP. */
    public record RegisterVerifyRequest(String challengeId, String email, String password, String otp) {}
    /** Nội dung yêu cầu gửi lại OTP: mã thử thách. */
    public record ChallengeRequest(String challengeId) {}
    /** Nội dung yêu cầu quên mật khẩu: định danh và khóa idempotency. */
    public record PasswordForgotRequest(String identifier, String idempotencyKey) {}
    /** Nội dung yêu cầu xác nhận OTP: mã thử thách và mã OTP. */
    public record OtpVerifyRequest(String challengeId, String otp) {}
    /** Nội dung yêu cầu đặt lại mật khẩu: token đặt lại và mật khẩu mới. */
    public record PasswordResetRequest(String resetToken, String password) {}
    /** Nội dung yêu cầu đăng nhập: email và mật khẩu. */
    public record LoginRequest(String email, String password) {}
    /** Nội dung yêu cầu làm mới token: refresh token và mã client. */
    public record RefreshRequest(String refreshToken, String clientId) {}

    /** Trả lỗi xác thực với mã trạng thái và mã lỗi của chính ngoại lệ. */
    @ExceptionHandler(AuthApplicationService.AuthException.class)
    ResponseEntity<AuthResponses.Error> handleAuth(AuthApplicationService.AuthException exception) {
        return ResponseEntity.status(exception.status()).body(new AuthResponses.Error(exception.code(), exception.getMessage(), null));
    }

    /** Đổi tham số không hợp lệ thành HTTP 400 {@code INVALID_REQUEST}. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<AuthResponses.Error> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new AuthResponses.Error("INVALID_REQUEST", exception.getMessage(), null));
    }

    /** Yêu cầu OTP quá sớm: HTTP 429 {@code OTP_COOLDOWN}. */
    @ExceptionHandler(AuthOtpChallengeService.CooldownException.class)
    ResponseEntity<AuthResponses.Error> handleCooldown() {
        return ResponseEntity.status(429).body(new AuthResponses.Error("OTP_COOLDOWN", "Please wait before requesting another code", null));
    }

    /** Vượt hạn mức gửi OTP: HTTP 429 {@code OTP_RATE_LIMITED}. */
    @ExceptionHandler(AuthOtpChallengeService.RateLimitException.class)
    ResponseEntity<AuthResponses.Error> handleRateLimit() {
        return ResponseEntity.status(429).body(new AuthResponses.Error("OTP_RATE_LIMITED", "Too many OTP requests", null));
    }

    /** Không thấy thử thách OTP: HTTP 404 {@code OTP_NOT_FOUND}. */
    @ExceptionHandler(AuthOtpChallengeService.ChallengeNotFoundException.class)
    ResponseEntity<AuthResponses.Error> handleNotFound() {
        return ResponseEntity.status(404).body(new AuthResponses.Error("OTP_NOT_FOUND", "Verification challenge was not found", null));
    }

    /** Thử thách OTP không còn dùng được: HTTP 410 {@code OTP_INVALID_STATE}. */
    @ExceptionHandler(AuthOtpChallengeService.InvalidStateException.class)
    ResponseEntity<AuthResponses.Error> handleInvalidState() {
        return ResponseEntity.status(410).body(new AuthResponses.Error("OTP_INVALID_STATE", "Verification challenge is no longer usable", null));
    }

    /** Khóa idempotency bị dùng lại cho yêu cầu khác: HTTP 409 {@code OTP_IDEMPOTENCY_REUSED}. */
    @ExceptionHandler(AuthOtpChallengeService.IdempotencyKeyReuseException.class)
    ResponseEntity<AuthResponses.Error> handleIdempotencyReuse() {
        return ResponseEntity.status(409).body(new AuthResponses.Error("OTP_IDEMPOTENCY_REUSED", "Request key cannot be reused", null));
    }

    /** Yêu cầu xung đột với yêu cầu khác: HTTP 409 {@code OTP_CONFLICT}. */
    @ExceptionHandler(AuthOtpChallengeService.ConflictException.class)
    ResponseEntity<AuthResponses.Error> handleConflict() {
        return ResponseEntity.status(409).body(new AuthResponses.Error("OTP_CONFLICT", "Verification request conflicted with another request", null));
    }
}
