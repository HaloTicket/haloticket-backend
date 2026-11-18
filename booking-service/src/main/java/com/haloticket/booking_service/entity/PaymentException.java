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
 * Một khoản thanh toán đã thu tiền nhưng đơn không thể hoàn tất (hoặc không phát hành được vé). Một người sẽ quyết định:
 * hoàn tiền thủ công trên cổng thanh toán rồi đánh dấu. Mỗi cặp (thanh toán, loại) có một dòng; {@code ISSUANCE_FAILED} không có mã thanh toán và
 * là duy nhất theo (đơn, loại) nhờ chỉ mục một phần (xem migration 20261031_payment_exceptions.sql).
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

    /** Trước khi lưu lần đầu: gán thời gian tạo nếu chưa có và đặt trạng thái mặc định {@code OPEN}. */
    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = PaymentExceptionStatus.OPEN;
    }
}
