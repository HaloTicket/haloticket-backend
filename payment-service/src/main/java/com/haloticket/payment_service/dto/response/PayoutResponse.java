package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PayoutStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

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
    private String requestType;
    private String transferReference;
    private String accountHolderSnapshot;
    private String maskedAccountNumber;
    private String bankNameSnapshot;
    private AccountSnapshot accountSnapshot;
    private Boolean canCancel;
    private String publicReason;
    private String quoteId;
    private String receiptUrl;
    private String eventName;
    private LocalDateTime eventEndAt;
    private String requestedAmount;
    private LocalDateTime approvedAt;
    private LocalDateTime processingAt;
    private Integer advanceRateSnapshot;
    private Long policyVersionSnapshot;
    private String policyScopeSnapshot;
    private String availableAmountSnapshot;
    private String advanceBaseSnapshot;
    private String advanceLimitSnapshot;
    private String paidAmountSnapshot;
    private String reservedAmountSnapshot;
    private List<TimelineEntry> timeline;
    @Data @Builder public static class AccountSnapshot {
        private String bankName;
        private String accountHolder;
        private String accountNumberMasked;
    }
    @Data @Builder public static class TimelineEntry {
        private String status;
        private LocalDateTime at;
        private String reason;
        private LocalDateTime occurredAt;
        private String publicMessage;
    }
}
