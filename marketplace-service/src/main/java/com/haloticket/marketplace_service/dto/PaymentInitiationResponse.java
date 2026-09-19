package com.haloticket.marketplace_service.dto;
import java.math.BigDecimal;
import java.time.Instant;
public record PaymentInitiationResponse(String transactionId, BigDecimal amount, String currency, Instant expiresAt) {}
