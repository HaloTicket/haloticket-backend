package com.haloticket.ticket_service.entity;

import com.haloticket.ticket_service.enums.ResaleListingStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

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

    @PrePersist
    protected void onCreate() {
        this.listedAt = LocalDateTime.now();
        if (this.status == null) this.status = ResaleListingStatus.LISTED;
    }
}
