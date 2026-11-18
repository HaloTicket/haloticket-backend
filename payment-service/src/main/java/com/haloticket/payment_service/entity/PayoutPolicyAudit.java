package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Nhật ký thay đổi chính sách chi trả (bảng {@code payout_policy_audit}): giá trị cũ và mới, người thay đổi, lý do. */
@Entity @Table(name="payout_policy_audit") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutPolicyAudit {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
    @Column(nullable=false) private String scope;
    @Column(name="target_id") private String targetId;
    @Column(name="old_advance_rate") private Integer oldAdvanceRate;
    @Column(name="new_advance_rate") private Integer newAdvanceRate;
    @Column(name="old_enabled") private Boolean oldEnabled;
    @Column(name="new_enabled") private Boolean newEnabled;
    @Column(name="old_processing_time") private String oldProcessingTime;
    @Column(name="new_processing_time") private String newProcessingTime;
    @Column(name="actor_id", nullable=false) private String actorId;
    @Column(nullable=false) private String reason;
    @Column(nullable=false) private LocalDateTime changedAt;
}
