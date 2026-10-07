package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PayoutStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class PayoutResponse {
    private String id;
    private String eventId;
    private String organizerUserId;
    private Double grossAmount;
    private Double platformFee;
    private Double netAmount;
    private PayoutStatus status;
    private LocalDateTime transferredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
