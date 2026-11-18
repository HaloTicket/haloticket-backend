package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PayoutStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** Yêu cầu chi trả trả về client: số tiền, trạng thái, mốc thời gian, ảnh chụp tài khoản và chính sách, dòng thời gian. */
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
    /** Ảnh chụp tài khoản nhận tiền với số tài khoản đã che. */
    @Data @Builder public static class AccountSnapshot {
        private String bankName;
        private String accountHolder;
        private String accountNumberMasked;
    }
    /** Một mốc trong dòng thời gian của yêu cầu chi trả: trạng thái, thời điểm, lý do và thông điệp công khai. */
    @Data @Builder public static class TimelineEntry {
        private String status;
        private LocalDateTime at;
        private String reason;
        private LocalDateTime occurredAt;
        private String publicMessage;
    }
}
