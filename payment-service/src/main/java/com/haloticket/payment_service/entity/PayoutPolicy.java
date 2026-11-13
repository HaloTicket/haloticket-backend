package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name="payout_policies") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutPolicy {
    @Id private String id;
    @Column(nullable=false) private Integer advanceRate;
    @Column(nullable=false) private Boolean advanceEnabled;
    @Column(nullable=false) private String processingTime;
    @Column(nullable=false) private Long version;
    @Version private Long rowVersion;
    private String updatedBy;
    private String reason;
    private LocalDateTime updatedAt;
    @PrePersist @PreUpdate void updateTimestamp() { updatedAt = LocalDateTime.now(); }
}
