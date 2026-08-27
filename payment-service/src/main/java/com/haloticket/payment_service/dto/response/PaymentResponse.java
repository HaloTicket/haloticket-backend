package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentResponse {
    private String id;
    private String bookingId;
    private Double amount;
    private PaymentStatus status;
    private String paymentUrl;
}