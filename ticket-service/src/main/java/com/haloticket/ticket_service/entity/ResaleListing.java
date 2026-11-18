package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.ResaleListingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** Tin đăng bán lại một vé trên chợ vé (bảng {@code resale_listings}): giá gốc, giá bán lại và trạng thái tin đăng. */
@Entity
@Table(name = "resale_listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResaleListing {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "seller_user_id", nullable = false)
    private String sellerUserId;

    @Column(name = "original_price", nullable = false)
    private Double originalPrice;

    @Column(name = "resale_price", nullable = false)
    private Double resalePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResaleListingStatus status;

    @Column(name = "listed_at", updatable = false)
    private LocalDateTime listedAt;

    @Column(name = "sold_at")
    private LocalDateTime soldAt;

    /** Khi lưu: ghi thời điểm đăng và mặc định trạng thái {@code LISTED}. */
    @PrePersist
    protected void onCreate() {
        this.listedAt = LocalDateTime.now();
        if (this.status == null) this.status = ResaleListingStatus.LISTED;
    }
}
