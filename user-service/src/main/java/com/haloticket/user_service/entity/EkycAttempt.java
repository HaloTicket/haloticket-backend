package com.haloticket.user_service.entity;

import com.haloticket.user_service.enums.EkycAttemptStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Một lần thử xác minh eKYC của người dùng, duy nhất theo cặp người dùng và khóa idempotency. */
@Entity
@Table(
        name = "ekyc_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ekyc_attempt_user_idempotency",
                columnNames = {"user_id", "idempotency_key"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EkycAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "consent_version", nullable = false, length = 50)
    private String consentVersion;

    @Column(name = "provider_reference", length = 200)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EkycAttemptStatus status;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "match_score")
    private Double matchScore;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Gán thời điểm tạo và cập nhật, và đặt trạng thái mặc định {@code CREATED} nếu chưa có. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        status = status == null ? EkycAttemptStatus.CREATED : status;
    }

    /** Làm mới thời điểm cập nhật mỗi khi bản ghi thay đổi. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
