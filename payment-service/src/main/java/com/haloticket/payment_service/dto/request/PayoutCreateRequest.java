package com.haloticket.payment_service.dto.request;

import lombok.Data;

@Data
public class PayoutCreateRequest {
    private String eventId;
    private String type;
    private String quoteId;
    private Long accountVersion;
    private Long requestedAmount;
    private Double requestAmount;
    private Double platformFeeRate;
}
