package com.haloticket.payment_service.entity;

import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "organizer_payout_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizerPayoutAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "organizer_user_id", nullable = false)
    private String organizerUserId;

    @Column(name = "account_holder", nullable = false)
    private String accountHolder;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

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

    @Column(name = "is_verified")
    private Boolean isVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status")
    private PayoutAccountVerificationStatus verificationStatus;

    @Column(name = "account_version")
    private Long accountVersion;

    @Column(name = "identity_version")
    private Long identityVersion;

    @Column(name = "verification_reference")
    private String verificationReference;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.isVerified == null) this.isVerified = false;
        if (this.verificationStatus == null) this.verificationStatus = PayoutAccountVerificationStatus.UNVERIFIED;
        if (this.accountVersion == null) this.accountVersion = 1L;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
