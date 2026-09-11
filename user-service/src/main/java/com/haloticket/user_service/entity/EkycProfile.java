package com.haloticket.user_service.entity;

import com.haloticket.user_service.enums.EkycDocumentType;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "ekyc_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EkycProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // foreign key linking back to User
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private EkycDocumentType documentType;

    @Column(name = "document_number")
    private String documentNumber;

    @Column(name = "document_number_hash", unique = true, length = 64)
    private String documentNumberHash;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "front_image_url")
    private String frontImageUrl;

    @Column(name = "back_image_url")
    private String backImageUrl;

    @Column(name = "face_video_url")
    private String faceVideoUrl;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_name")
    private String bankAccountName;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_branch")
    private String bankBranch;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "tax_name")
    private String taxName;

    @Column(name = "tax_code")
    private String taxCode;

    @Column(name = "tax_address")
    private String taxAddress;

    @Enumerated(EnumType.STRING)
    private EkycVerificationStatus status;

    @Column(name = "identity_version")
    private Long identityVersion;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_attempt_id")
    private EkycAttempt acceptedAttempt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    private LocalDate documentExpiresOn;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = EkycVerificationStatus.PENDING;
        }
        if (this.identityVersion == null) {
            this.identityVersion = 1L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
