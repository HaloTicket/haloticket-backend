package com.haloticket.payment_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Báo giá chi trả đã phát hành (bảng {@code payout_quotes}): số tiền yêu cầu, ảnh chụp chính sách và đối soát, hạn hiệu lực
 * và cờ đã dùng để mỗi báo giá chỉ được dùng một lần.
 */
@Entity @Table(name = "payout_quotes") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayoutQuote {
    @Id private String id;
    @Column(nullable = false) private String organizerUserId;
    @Column(nullable = false) private String eventId;
    @Column(nullable = false) private String requestType;
    @Column(nullable = false) private Long requestAmount;
    @Column(nullable = false) private Long availableAmount;
    @Column(nullable = false) private Long grossSnapshot;
    @Column(nullable = false) private Long platformFeesSnapshot;
    @Column(nullable = false) private Integer advanceRateSnapshot;
    @Column(nullable = false) private Long policyVersionSnapshot;
    @Column(nullable = false) private String policyScopeSnapshot;
    @Column(nullable = false) private Long advanceBaseSnapshot;
    @Column(nullable = false) private Long advanceLimitSnapshot;
    @Column(nullable = false) private Long paidAmountSnapshot;
    @Column(nullable = false) private Long reservedAmountSnapshot;
    @Column(nullable = false) private Long accountVersion;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Column(nullable = false) private Boolean consumed;
}
