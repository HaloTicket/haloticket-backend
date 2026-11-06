package com.haloticket.booking_service.entity;

import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A payment that was taken but the order could not be completed (or tickets could not be issued). A person decides:
 * refund on the gateway by hand and mark it. One row per (payment, kind); ISSUANCE_FAILED has no payment id and is
 * unique per (booking, kind) through a partial index (see migration 20261031_payment_exceptions.sql).
 */
@Entity
@Table(name = "payment_exceptions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_exceptions_payment_kind", columnNames = {"payment_id", "kind"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentException {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "booking_id", nullable = false)
    private String bookingId;

    @Column(name = "payment_id")
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40)
    private PaymentExceptionKind kind;

    private Double amount;

    @Column(name = "provider", length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentExceptionStatus status;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = PaymentExceptionStatus.OPEN;
    }
}
