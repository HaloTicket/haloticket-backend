package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.TicketOperationState;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Thao tác khóa hoặc chuyển quyền sở hữu một vé (bảng {@code ticket_operations}), định danh duy nhất bằng {@code operationId}, dùng cho saga bán lại và chuyển nhượng. */
@Entity
@Table(name = "ticket_operations", uniqueConstraints = @UniqueConstraint(name = "uk_ticket_operation_id", columnNames = "operation_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TicketOperation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private String id;
    @Column(name = "operation_id", nullable = false, unique = true) private String operationId;
    @Column(name = "saga_id") private String sagaId;
    @Column(name = "ticket_id", nullable = false) private String ticketId;
    @Column(name = "actor_id") private String actorId;
    @Column(name = "expected_owner_id") private String expectedOwnerId;
    @Column(nullable = false) private String purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TicketOperationState state;
    @Column(name = "last_error") private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Version private Long version;
    /** Khi lưu: ghi thời điểm tạo và cập nhật. */
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    /** Khi cập nhật: ghi lại thời điểm cập nhật. */
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
