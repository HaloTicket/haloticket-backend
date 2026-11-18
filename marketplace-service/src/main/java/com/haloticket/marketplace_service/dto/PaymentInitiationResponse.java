package com.haloticket.marketplace_service.dto;
import java.math.BigDecimal;
import java.time.Instant;
/** Kết quả khởi tạo thanh toán cho giao dịch mua vé bán lại: số tiền, tiền tệ và thời hạn thanh toán. */
public record PaymentInitiationResponse(String transactionId, BigDecimal amount, String currency, Instant expiresAt) {}
