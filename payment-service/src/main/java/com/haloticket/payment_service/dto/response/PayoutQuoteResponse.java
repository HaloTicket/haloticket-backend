package com.haloticket.payment_service.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** Báo giá chi trả: số tiền gộp, khấu trừ, khả dụng, phí, số tiền dự kiến nhận, hạn hiệu lực và lý do nếu không đủ điều kiện. */
@Data @Builder
public class PayoutQuoteResponse {
    private String quoteId, eventId, type, grossAmount, deductions, availableAmount, maximumAmount,
            payoutFee, expectedTransferAmount, processingTime;
    private LocalDateTime expiresAt;
    private Long accountVersion;
    private boolean eligible;
    private List<String> reasonCodes;
    private Account account;
    /** Tài khoản nhận tiền trong báo giá, số tài khoản được che. */
    @Data @Builder public static class Account { private String accountHolder, maskedAccountNumber, bankName; }
}
