package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

/** Thông tin thanh toán trả về client: mã, đơn, số tiền, trạng thái và URL thanh toán. */
@Data
@Builder
public class PaymentResponse {
    private String id;
    private String bookingId;
    private Double amount;
    private PaymentStatus status;
    private String paymentUrl;
}