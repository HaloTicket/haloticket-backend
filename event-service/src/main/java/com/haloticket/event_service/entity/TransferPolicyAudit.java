package com.haloticket.event_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/** Thực thể nhật ký kiểm toán thay đổi chính sách chuyển nhượng (bảng {@code transfer_policy_audit}): người sửa, phiên bản, nội dung trước và sau dạng JSON. */
@Entity @Table(name = "transfer_policy_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransferPolicyAudit {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "event_id", nullable = false) private String eventId;
    @Column(nullable = false) private String feature;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private long version;
    @Column(name = "before_json", columnDefinition = "TEXT") private String beforeJson;
    @Column(name = "after_json", nullable = false, columnDefinition = "TEXT") private String afterJson;
    @Column(name = "changed_at", nullable = false) private Instant changedAt;
}
