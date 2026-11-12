package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name="payout_policy_overrides", uniqueConstraints=@UniqueConstraint(columnNames={"scope","target_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutPolicyOverride {
    @Id private String id;
    @Column(nullable=false) private String scope;
    @Column(name="target_id", nullable=false) private String targetId;
    @Column(nullable=false) private Integer advanceRate;
    @Column(nullable=false) private Boolean advanceEnabled;
    @Column(nullable=false) private Long version;
    @Version private Long rowVersion;
    private String updatedBy;
    private String reason;
    private LocalDateTime updatedAt;
    @PrePersist @PreUpdate void updateTimestamp() { updatedAt=LocalDateTime.now(); }
}
