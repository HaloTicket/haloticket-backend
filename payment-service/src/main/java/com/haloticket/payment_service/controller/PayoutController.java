package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.service.PayoutService;
import com.haloticket.payment_service.repository.PayoutReconciliationRepository;
import com.haloticket.payment_service.entity.PayoutReconciliation;
import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.PayoutRepository;
import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import com.haloticket.payment_service.client.EventServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.List;
import java.util.Map;

/**
 * API chi trả {@code /api/payouts}: admin duyệt, từ chối, tranh chấp, ghi nhận chuyển khoản và xem tổng hợp;
 * ban tổ chức xem số dư, báo giá và gửi yêu cầu chi trả.
 */
@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;
    private final PayoutReconciliationRepository reconciliationRepository;
    private final OrganizerPayoutAccountRepository payoutAccountRepository;
    private final PayoutRepository payoutRepository;
    private final EventServiceClient eventServiceClient;

    // các endpoint dành cho admin
    /**
     * Liệt kê các yêu cầu chi trả đang chờ admin xử lý.
     *
     * @return danh sách yêu cầu chờ duyệt
     */
    @GetMapping("/admin/pending")
    public ResponseEntity<List<PayoutResponse>> getPendingPayouts() {
        return ResponseEntity.ok(payoutService.getPendingPayouts());
    }

    /**
     * Liệt kê mọi yêu cầu chi trả cho admin.
     *
     * @return danh sách yêu cầu chi trả
     */
    @GetMapping("/admin/all")
    public ResponseEntity<List<PayoutResponse>> getAllAdminPayouts() {
        return ResponseEntity.ok(payoutService.getAllAdminPayouts());
    }

    /**
     * Tổng hợp tài chính nền tảng: doanh thu đã thu, phí nền tảng, tiền đang giữ (từ các đối soát đã xác nhận),
     * tiền đang xử lý (chờ duyệt, đã duyệt, đang chuyển, cần xác minh, tranh chấp), tiền đã chi và số yêu cầu theo trạng thái.
     *
     * @return số liệu tổng hợp, số tiền dạng chuỗi
     */
    @GetMapping("/admin/summary")
    public ResponseEntity<AdminFinanceSummary> getAdminFinanceSummary() {
        List<PayoutReconciliation> confirmed = reconciliationRepository.findAll().stream()
                .filter(r -> r.getConfirmedAt() != null).toList();
        List<Payout> payouts = payoutRepository.findAll();
        Map<String, Long> counts = payouts.stream().collect(java.util.stream.Collectors.groupingBy(
                p -> p.getStatus().name(), java.util.stream.Collectors.counting()));
        long paid = payouts.stream().filter(p -> p.getStatus() == com.haloticket.payment_service.enums.PayoutStatus.COMPLETED)
                .mapToLong(p -> p.getRequestedAmountVnd() == null ? (p.getNetAmount() == null ? 0 : Math.round(p.getNetAmount())) : p.getRequestedAmountVnd()).sum();
        long processing = payouts.stream().filter(p -> List.of(com.haloticket.payment_service.enums.PayoutStatus.PENDING,
                com.haloticket.payment_service.enums.PayoutStatus.APPROVED, com.haloticket.payment_service.enums.PayoutStatus.PROCESSING,
                com.haloticket.payment_service.enums.PayoutStatus.NEEDS_VERIFICATION, com.haloticket.payment_service.enums.PayoutStatus.DISPUTE)
                .contains(p.getStatus())).mapToLong(p -> p.getRequestedAmountVnd() == null ? (p.getNetAmount() == null ? 0 : Math.round(p.getNetAmount())) : p.getRequestedAmountVnd()).sum();
        return ResponseEntity.ok(new AdminFinanceSummary(
                Long.toString(confirmed.stream().mapToLong(PayoutReconciliation::getGrossCollected).sum()),
                Long.toString(confirmed.stream().mapToLong(PayoutReconciliation::getPlatformFees).sum()),
                Long.toString(confirmed.stream().mapToLong(PayoutReconciliation::getHeldAmount).sum()),
                Long.toString(processing), Long.toString(paid), counts));
    }

    /**
     * Admin xác minh tài khoản ngân hàng của ban tổ chức bằng mã tham chiếu ngân hàng.
     *
     * @param organizerId mã người dùng của ban tổ chức
     * @param jwt token của admin (ghi nhận người xác minh)
     * @param body phải có {@code verificationReference}
     * @return tài khoản đã được đánh dấu xác minh
     * @throws ResponseStatusException 400 khi thiếu mã tham chiếu, 404 khi chưa có tài khoản
     */
    @PutMapping("/admin/organizers/{organizerId}/payout-account/verify")
    public ResponseEntity<?> verifyOrganizerPayoutAccount(@PathVariable String organizerId,
            @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> body) {
        String reference = body == null ? null : body.get("verificationReference");
        if (reference == null || reference.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "A bank verification reference is required");
        }
        var account = payoutAccountRepository.findForUpdate(organizerId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        account.setVerificationReference(reference);
        account.setVerificationStatus(PayoutAccountVerificationStatus.VERIFIED);
        account.setIsVerified(true);
        account.setVerifiedAt(java.time.LocalDateTime.now());
        account.setVerifiedBy(jwt.getSubject());
        return ResponseEntity.ok(payoutAccountRepository.save(account));
    }

    /**
     * Admin duyệt một yêu cầu chi trả.
     *
     * @param id mã yêu cầu
     * @return yêu cầu sau khi duyệt
     */
    @PostMapping("/admin/{id}/approve")
    public ResponseEntity<PayoutResponse> approvePayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.approvePayout(id));
    }

    /**
     * Admin đánh dấu yêu cầu chi trả đang tranh chấp.
     *
     * @param id mã yêu cầu
     * @param body có thể chứa {@code reason}
     * @return yêu cầu sau cập nhật
     */
    @PostMapping("/admin/{id}/dispute")
    public ResponseEntity<PayoutResponse> disputePayout(
            @PathVariable String id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(payoutService.disputePayout(id, reason));
    }

    /**
     * Admin mở lại yêu cầu chi trả đã bị tranh chấp hoặc từ chối.
     *
     * @param id mã yêu cầu
     * @return yêu cầu sau cập nhật
     */
    @PostMapping("/admin/{id}/reopen")
    public ResponseEntity<PayoutResponse> reopenPayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.reopenPayout(id));
    }

    /**
     * Admin từ chối yêu cầu chi trả.
     *
     * @param id mã yêu cầu
     * @param body có thể chứa {@code reason}
     * @return yêu cầu sau cập nhật
     */
    @PostMapping("/admin/{id}/reject")
    public ResponseEntity<PayoutResponse> rejectPayout(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(payoutService.rejectPayout(id, body == null ? null : body.get("reason")));
    }

    /**
     * Chi tiết một yêu cầu chi trả cho admin: tên sự kiện, ảnh chụp tài khoản nhận, trạng thái xác minh tài khoản,
     * các thao tác được phép theo trạng thái và dòng thời gian.
     *
     * @param id mã yêu cầu
     * @return chi tiết yêu cầu
     * @throws ResponseStatusException HTTP 404 khi không có yêu cầu
     */
    @GetMapping("/admin/{id}")
    public ResponseEntity<AdminPayoutDetail> getAdminDetail(@PathVariable String id) {
        var payout = payoutRepository.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        String eventName = eventServiceClient.getAllEvents().stream().filter(event -> payout.getEventId().equals(event.getId()))
                .map(com.haloticket.payment_service.dto.response.EventFinanceSource::getTitle).findFirst().orElse(null);
        boolean accountVerified = payoutAccountRepository.findByOrganizerUserId(payout.getOrganizerUserId())
                .map(account -> Boolean.TRUE.equals(account.getIsVerified())).orElse(false);
        PayoutResponse payoutResponse = payoutService.getAllAdminPayouts().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
        List<String> actions = switch (payout.getStatus()) {
            case PENDING -> List.of("APPROVE", "REJECT", "DISPUTE");
            case APPROVED -> List.of("RECORD_TRANSFER");
            case PROCESSING -> List.of("RECORD_TRANSFER");
            default -> List.of();
        };
        return ResponseEntity.ok(new AdminPayoutDetail(payout.getId(), payout.getEventId(), eventName, payout.getOrganizerUserId(),
                payout.getStatus(), payout.getRequestType(), payout.getGrossAmount(), payout.getPlatformFee(), payout.getNetAmount(),
                new AccountSnapshot(payout.getAccountHolderSnapshot(), payout.getAccountNumberSnapshot(), payout.getBankNameSnapshot()),
                accountVerified, payout.getPublicReason(),
                payout.getTransferReference(), payout.getTransferReceiptUrl(), payout.getCreatedAt(), payout.getUpdatedAt(),
                payout.getTransferredAt(), actions, payoutResponse.getTimeline(), payoutResponse.getAdvanceRateSnapshot(),
                payoutResponse.getPolicyVersionSnapshot(), payoutResponse.getPolicyScopeSnapshot(), payoutResponse.getAvailableAmountSnapshot(),
                payoutResponse.getAdvanceBaseSnapshot(), payoutResponse.getAdvanceLimitSnapshot(), payoutResponse.getPaidAmountSnapshot(),
                payoutResponse.getReservedAmountSnapshot()));
    }

    /**
     * Admin bắt đầu quá trình chuyển tiền cho yêu cầu đã duyệt.
     *
     * @param id mã yêu cầu
     * @return yêu cầu sau cập nhật
     */
    @PostMapping("/admin/{id}/start")
    public ResponseEntity<PayoutResponse> startDisbursement(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.startDisbursement(id));
    }

    /**
     * Admin ghi nhận đã chuyển tiền: mã tham chiếu, biên lai và thời điểm chuyển ({@code transferredAt} có thể kèm hoặc không kèm offset).
     *
     * @param id mã yêu cầu
     * @param body gồm {@code reference}, {@code receiptUrl}, {@code transferredAt}
     * @return yêu cầu sau khi hoàn tất
     * @throws ResponseStatusException HTTP 400 khi {@code transferredAt} sai định dạng
     */
    @PostMapping("/admin/{id}/complete")
    public ResponseEntity<PayoutResponse> completeDisbursement(@PathVariable String id, @RequestBody Map<String, String> body) {
        java.time.LocalDateTime transferredAt = null;
        try {
            if (body != null && body.get("transferredAt") != null) {
                String value = body.get("transferredAt");
                try { transferredAt = java.time.OffsetDateTime.parse(value).toLocalDateTime(); }
                catch (java.time.format.DateTimeParseException noOffset) { transferredAt = java.time.LocalDateTime.parse(value); }
            }
        }
        catch (RuntimeException invalid) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid transferredAt"); }
        return ResponseEntity.ok(payoutService.completeDisbursement(id, body == null ? null : body.get("reference"),
                transferredAt, body == null ? null : body.get("receiptUrl")));
    }

    // các endpoint dành cho ban tổ chức
    /**
     * Liệt kê các yêu cầu chi trả của ban tổ chức đang đăng nhập.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @return danh sách yêu cầu
     */
    @GetMapping("/organizer")
    public ResponseEntity<List<PayoutResponse>> getOrganizerPayouts(@RequestHeader("Authorization") String authorization,
                                                                     @AuthenticationPrincipal Jwt jwt) {
        String organizerId = payoutService.resolveOrganizerId(authorization);
        return ResponseEntity.ok(payoutService.getOrganizerPayouts(organizerId));
    }

    /**
     * Chi tiết một yêu cầu chi trả của ban tổ chức, bổ sung tên và giờ kết thúc sự kiện khi event-service trả lời được.
     *
     * @param authorization header Authorization
     * @param id mã yêu cầu
     * @return chi tiết yêu cầu
     */
    @GetMapping("/organizer/{id}")
    public ResponseEntity<PayoutResponse> getOrganizerDetail(@RequestHeader("Authorization") String authorization, @PathVariable String id) {
        var response = payoutService.getOrganizerPayout(payoutService.resolveOrganizerId(authorization), id);
        // Bổ sung tên sự kiện chỉ là phần phụ: event-service gặp sự cố không được làm ẩn
        // yêu cầu chi trả hợp lệ của ban tổ chức cùng trạng thái, số tiền và ảnh chụp tài khoản đã lưu.
        try {
            var events = eventServiceClient.getMyEvents(authorization);
            if (events != null) events.stream()
                    .filter(event -> response.getEventId() != null && response.getEventId().equals(event.getId()))
                    .findFirst().ifPresent(event -> {
                        response.setEventName(event.getTitle());
                        response.setEventEndAt(event.getEndTime());
                    });
        } catch (RuntimeException ignored) {
            response.setEventName(null);
            response.setEventEndAt(null);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Ban tổ chức hủy yêu cầu chi trả của mình.
     *
     * @param jwt token đang xác thực
     * @param authorization header Authorization
     * @param id mã yêu cầu
     * @return yêu cầu sau khi hủy
     */
    @PostMapping("/organizer/{id}/cancel")
    public ResponseEntity<PayoutResponse> cancelOrganizerPayout(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String authorization, @PathVariable String id) {
        return ResponseEntity.ok(payoutService.cancelPayout(payoutService.resolveOrganizerId(authorization), id));
    }

    /**
     * Tìm yêu cầu chi trả theo khóa idempotency mà ban tổ chức đã gửi.
     *
     * @param jwt token đang xác thực
     * @param authorization header Authorization
     * @param key khóa idempotency
     * @return yêu cầu tương ứng
     */
    @GetMapping("/organizer/requests/by-key/{key}")
    public ResponseEntity<PayoutResponse> getByIdempotencyKey(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String authorization, @PathVariable String key) {
        return ResponseEntity.ok(payoutService.getByIdempotencyKey(payoutService.resolveOrganizerId(authorization), key));
    }

    /**
     * Tổng hợp tài chính của ban tổ chức: đã thu, phí, hoàn tiền, đang giữ, khả dụng, đang xử lý, đã chi và từng sự kiện.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @return số liệu tài chính
     */
    @GetMapping("/organizer/summary")
    public ResponseEntity<com.haloticket.payment_service.dto.response.OrganizerFinanceResponse> getSummary(
            @RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization));
    }

    /**
     * Dòng tiền theo ngày của ban tổ chức. Mặc định 30 ngày gần nhất tính đến hôm nay; thiếu một đầu thì suy ra từ đầu còn lại.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @param from ngày bắt đầu, tùy chọn
     * @param to ngày kết thúc, tùy chọn
     * @param eventId lọc theo sự kiện, tùy chọn
     * @return chuỗi dòng tiền theo ngày
     */
    @GetMapping("/organizer/analytics")
    public ResponseEntity<com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse> getOrganizerAnalytics(
            @RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String eventId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (from == null && to == null) {
            to = today;
            from = today.minusDays(29);
        } else if (from == null) {
            from = to.minusDays(29);
        } else if (to == null) {
            to = today;
        }
        return ResponseEntity.ok(payoutService.getOrganizerAnalytics(payoutService.resolveOrganizerId(authorization), authorization, from, to, eventId));
    }

    // Giữ tương thích cho nơi gọi trực tiếp trong Java, còn tham số truy vấn tùy chọn vẫn tương thích ngược qua HTTP.
    /**
     * Phiên bản không có {@code eventId} (không lọc theo sự kiện), giữ cho nơi gọi trực tiếp trong Java.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @param from ngày bắt đầu
     * @param to ngày kết thúc
     * @return chuỗi dòng tiền theo ngày
     */
    public ResponseEntity<com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse> getOrganizerAnalytics(
            String authorization, Jwt jwt, java.time.LocalDate from, java.time.LocalDate to) {
        return getOrganizerAnalytics(authorization, jwt, from, to, null);
    }

    /**
     * Liệt kê tình hình tài chính từng sự kiện của ban tổ chức.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @return danh sách sự kiện kèm số liệu
     */
    @GetMapping("/organizer/events")
    public ResponseEntity<?> getOrganizerEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization).getItems());
    }

    /**
     * Liệt kê các sự kiện đủ điều kiện yêu cầu chi trả; hiện chỉ hỗ trợ loại {@code FINAL_SETTLEMENT}.
     *
     * @param authorization header Authorization
     * @param jwt token đang xác thực
     * @param type loại chi trả
     * @return danh sách sự kiện đủ điều kiện
     * @throws ResponseStatusException HTTP 400 khi loại không được hỗ trợ
     */
    @GetMapping("/organizer/eligible-events")
    public ResponseEntity<?> getEligibleEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "FINAL_SETTLEMENT") String type) {
        if (!"FINAL_SETTLEMENT".equals(type)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported payout type");
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization).getItems().stream()
                .filter(item -> item.getEligibility().isEligible()).toList());
    }

    /**
     * Gửi yêu cầu chi trả. Header {@code Idempotency-Key} giúp tránh tạo trùng khi gửi lại.
     *
     * @param authorization header Authorization
     * @param idempotencyKey khóa idempotency, tùy chọn
     * @param request nội dung yêu cầu
     * @return yêu cầu vừa tạo
     */
    @PostMapping("/organizer/request")
    public ResponseEntity<PayoutResponse> requestPayout(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PayoutCreateRequest request) {
        return ResponseEntity.ok(payoutService.requestPayout(payoutService.resolveOrganizerId(authorization), authorization, request, idempotencyKey));
    }

    /**
     * Báo giá một lần chi trả: số tiền khả dụng, phí và số tiền dự kiến nhận.
     *
     * @param authorization header Authorization
     * @param request sự kiện, loại và số tiền yêu cầu
     * @return báo giá
     */
    @PostMapping("/organizer/quote")
    public ResponseEntity<com.haloticket.payment_service.dto.response.PayoutQuoteResponse> quote(
            @RequestHeader("Authorization") String authorization, @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(payoutService.quotePayout(payoutService.resolveOrganizerId(authorization), authorization, request.eventId(), request.type(), request.requestedAmount()));
    }


    /** Nội dung yêu cầu báo giá: sự kiện, loại chi trả và số tiền yêu cầu. */
    public record QuoteRequest(String eventId, String type, Long requestedAmount) {}
    /** Chi tiết yêu cầu chi trả cho admin, gồm số tiền, ảnh chụp tài khoản, các thao tác được phép, dòng thời gian và các ảnh chụp chính sách. */
    public record AdminPayoutDetail(String id, String eventId, String eventName, String organizerUserId,
            com.haloticket.payment_service.enums.PayoutStatus status, String requestType, Double grossAmount,
            Double platformFee, Double netAmount, AccountSnapshot accountSnapshot, Boolean accountVerified,
            String publicReason, String transferReference, String receiptUrl, java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt, java.time.LocalDateTime transferredAt, List<String> allowedActions,
            List<PayoutResponse.TimelineEntry> timeline, Integer advanceRateSnapshot, Long policyVersionSnapshot,
            String policyScopeSnapshot, String availableAmountSnapshot, String advanceBaseSnapshot,
            String advanceLimitSnapshot, String paidAmountSnapshot, String reservedAmountSnapshot) {}
    /** Ảnh chụp tài khoản nhận tiền tại thời điểm yêu cầu: chủ tài khoản, số tài khoản, ngân hàng. */
    public record AccountSnapshot(String accountHolder, String accountNumber, String bankName) {}

    /** Tổng hợp tài chính nền tảng cho admin: số tiền dạng chuỗi và số yêu cầu theo trạng thái. */
    public record AdminFinanceSummary(String collectedRevenue, String platformFees, String heldAmount,
            String processingAmount, String paidAmount, Map<String, Long> countsByStatus) {}
}
