package com.haloticket.user_service.controller;

import com.haloticket.user_service.auth.service.AuthApplicationService;
import com.haloticket.user_service.auth.service.AuthRequests;
import com.haloticket.user_service.auth.service.AuthResponses;
import com.haloticket.user_service.auth.service.AuthOtpChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthApplicationService service;

    public AuthController(AuthApplicationService service) { this.service = service; }

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

    @PostMapping("/register/verify")
    public ResponseEntity<Void> registerVerify(@RequestBody RegisterVerifyRequest request) {
        service.verifyRegistration(new AuthRequests.RegisterVerify(request.challengeId(), request.email(), request.password(), request.otp()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register/resend")
    public ResponseEntity<AuthResponses.Challenge> registerResend(@RequestBody ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(service.resendRegistration(new AuthRequests.Challenge(request.challengeId(), null))));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<AuthResponses.Challenge> passwordForgot(@RequestBody PasswordForgotRequest request,
                                                                   HttpServletRequest http) {
        validateEmail(request.email());
        requireText(request.phoneNumber(), "phoneNumber");
        requireText(request.idempotencyKey(), "idempotencyKey");
        var result = service.startPasswordReset(new AuthRequests.PasswordForgot(request.email(), request.phoneNumber(),
                request.idempotencyKey(), ipHash(http)));
        return ResponseEntity.accepted().body(challenge(result));
    }

    @PostMapping("/password/verify")
    public ResponseEntity<AuthResponses.ResetToken> passwordVerify(@RequestBody OtpVerifyRequest request) {
        return ResponseEntity.ok(new AuthResponses.ResetToken(service.verifyPasswordReset(
                new AuthRequests.OtpVerify(request.challengeId(), request.otp())), Instant.now().plusSeconds(600)));
    }

    @PostMapping("/password/resend")
    public ResponseEntity<AuthResponses.Challenge> passwordResend(@RequestBody ChallengeRequest request) {
        return ResponseEntity.accepted().body(challenge(service.resendPasswordReset(new AuthRequests.Challenge(request.challengeId(), null))));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> passwordReset(@RequestBody PasswordResetRequest request) {
        requireText(request.resetToken(), "resetToken");
        validatePassword(request.password());
        service.resetPassword(new AuthRequests.PasswordReset(request.resetToken(), request.password()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponses.Login> login(@RequestBody LoginRequest request) {
        validateEmail(request.email());
        requireText(request.password(), "password");
        return ResponseEntity.ok(service.login(new AuthRequests.Login(request.email(), request.password())));
    }

    private AuthResponses.Challenge challenge(AuthOtpChallengeService.CreateResult result) {
        return new AuthResponses.Challenge(result.challenge().getId(), result.challenge().getExpiresAt(), result.challenge().getResendAvailableAt());
    }

    private String ipHash(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-IP-Hash");
        return supplied == null || supplied.isBlank() ? "anonymous" : supplied.trim();
    }

    private void validateEmail(String value) {
        if (value == null || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new AuthApplicationService.AuthException("EMAIL_INVALID", "Email is invalid", 400);
    }

    private void validatePassword(String value) {
        if (value == null || value.length() < 8 || value.length() > 32 || !value.matches(".*[a-z].*")
                || !value.matches(".*[A-Z].*") || !value.matches(".*\\d.*") || !value.matches(".*[^A-Za-z0-9].*"))
            throw new AuthApplicationService.AuthException("PASSWORD_INVALID", "Password does not meet policy", 400);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new AuthApplicationService.AuthException("INVALID_REQUEST", field + " is required", 400);
    }

    public record RegisterStartRequest(String email, String password, String idempotencyKey) {}
    public record RegisterVerifyRequest(String challengeId, String email, String password, String otp) {}
    public record ChallengeRequest(String challengeId) {}
    public record PasswordForgotRequest(String email, String phoneNumber, String idempotencyKey) {}
    public record OtpVerifyRequest(String challengeId, String otp) {}
    public record PasswordResetRequest(String resetToken, String password) {}
    public record LoginRequest(String email, String password) {}

    @ExceptionHandler(AuthApplicationService.AuthException.class)
    ResponseEntity<AuthResponses.Error> handleAuth(AuthApplicationService.AuthException exception) {
        return ResponseEntity.status(exception.status()).body(new AuthResponses.Error(exception.code(), exception.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<AuthResponses.Error> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new AuthResponses.Error("INVALID_REQUEST", exception.getMessage(), null));
    }

    @ExceptionHandler(AuthOtpChallengeService.CooldownException.class)
    ResponseEntity<AuthResponses.Error> handleCooldown() {
        return ResponseEntity.status(429).body(new AuthResponses.Error("OTP_COOLDOWN", "Please wait before requesting another code", null));
    }

    @ExceptionHandler(AuthOtpChallengeService.RateLimitException.class)
    ResponseEntity<AuthResponses.Error> handleRateLimit() {
        return ResponseEntity.status(429).body(new AuthResponses.Error("OTP_RATE_LIMITED", "Too many OTP requests", null));
    }

    @ExceptionHandler(AuthOtpChallengeService.ChallengeNotFoundException.class)
    ResponseEntity<AuthResponses.Error> handleNotFound() {
        return ResponseEntity.status(404).body(new AuthResponses.Error("OTP_NOT_FOUND", "Verification challenge was not found", null));
    }

    @ExceptionHandler(AuthOtpChallengeService.InvalidStateException.class)
    ResponseEntity<AuthResponses.Error> handleInvalidState() {
        return ResponseEntity.status(410).body(new AuthResponses.Error("OTP_INVALID_STATE", "Verification challenge is no longer usable", null));
    }

    @ExceptionHandler(AuthOtpChallengeService.IdempotencyKeyReuseException.class)
    ResponseEntity<AuthResponses.Error> handleIdempotencyReuse() {
        return ResponseEntity.status(409).body(new AuthResponses.Error("OTP_IDEMPOTENCY_REUSED", "Request key cannot be reused", null));
    }

    @ExceptionHandler(AuthOtpChallengeService.ConflictException.class)
    ResponseEntity<AuthResponses.Error> handleConflict() {
        return ResponseEntity.status(409).body(new AuthResponses.Error("OTP_CONFLICT", "Verification request conflicted with another request", null));
    }
}
