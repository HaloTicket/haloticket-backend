package com.haloticket.user_service.entity;

import jakarta.persistence.*;
import lombok.*;
import com.haloticket.user_service.enums.OtpPurpose;
import com.haloticket.user_service.enums.OtpStatus;

import java.time.Instant;

/** Một lượt thử thách OTP (đăng ký hoặc đặt lại mật khẩu): lưu mã băm OTP, hạn dùng, số lần gửi và số lần nhập sai. */
@Entity
@Table(name = "auth_otp_challenges", indexes = {
        @Index(name = "idx_otp_subject_purpose", columnList = "subject,purpose"),
        @Index(name = "idx_otp_expires_at", columnList = "expires_at"),
        @Index(name = "idx_otp_idempotency", columnList = "idempotency_key,purpose,subject", unique = true)
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthOtpChallenge {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OtpPurpose purpose;

    @Column(nullable = false, length = 320)
    private String subject;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "otp_hash", nullable = false, length = 128)
    private String otpHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OtpStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_available_at", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "send_window_started_at", nullable = false)
    private Instant sendWindowStartedAt;

    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "request_ip_hash", length = 128)
    private String requestIpHash;

    private Instant verifiedAt;
    private Instant consumedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Gán thời điểm tạo và cập nhật trước khi lưu lần đầu. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /** Làm mới thời điểm cập nhật mỗi khi bản ghi thay đổi. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
