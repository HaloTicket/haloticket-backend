package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Chính sách ứng trước chi trả mặc định (bảng {@code payout_policies}): tỷ lệ ứng, bật tắt, thời gian xử lý; có khóa phiên bản lạc quan. */
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
    /** Trước khi lưu hoặc cập nhật: làm mới thời điểm cập nhật. */
    @PrePersist @PreUpdate void updateTimestamp() { updatedAt = LocalDateTime.now(); }
}
