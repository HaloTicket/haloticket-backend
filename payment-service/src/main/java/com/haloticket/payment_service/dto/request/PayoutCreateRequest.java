package com.haloticket.payment_service.dto.request;

import lombok.Data;

@Data
public class PayoutCreateRequest {
    private String eventId;
    private Double requestAmount;
    private Double platformFeeRate;
}
