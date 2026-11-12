package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
public class PayoutQuoteResponse {
    private String quoteId, eventId, type, grossAmount, deductions, availableAmount, maximumAmount,
            payoutFee, expectedTransferAmount, processingTime;
    private LocalDateTime expiresAt;
    private Long accountVersion;
    private boolean eligible;
    private List<String> reasonCodes;
    private Account account;
    @Data @Builder public static class Account { private String accountHolder, maskedAccountNumber, bankName; }
}
