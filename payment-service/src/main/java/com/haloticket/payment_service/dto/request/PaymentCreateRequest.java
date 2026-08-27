package com.haloticket.payment_service.dto.request;

import com.haloticket.payment_service.enums.PaymentMethod;
import com.haloticket.payment_service.enums.PaymentProvider;
import lombok.Data;

@Data
public class PaymentCreateRequest {
    private String bookingId;
    private Double amount;
    private PaymentMethod method;
    private PaymentProvider provider;
}