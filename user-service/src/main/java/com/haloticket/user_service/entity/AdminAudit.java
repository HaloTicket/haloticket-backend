package com.haloticket.user_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One row per admin action on a user account: who did what to whom, what it was before and what it is now. */
@Entity
@Table(name = "admin_audit", indexes = {
        @Index(name = "idx_admin_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_admin_audit_target", columnList = "target_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAudit {
    public static final String CREATE_USER = "CREATE_USER";
    public static final String CHANGE_STATUS = "CHANGE_STATUS";
    public static final String CHANGE_ROLES = "CHANGE_ROLES";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "actor_keycloak_id", nullable = false)
    private String actorKeycloakId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "target_email")
    private String targetEmail;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
