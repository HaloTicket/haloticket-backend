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

@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;
    private final PayoutReconciliationRepository reconciliationRepository;
    private final OrganizerPayoutAccountRepository payoutAccountRepository;
    private final PayoutRepository payoutRepository;
    private final EventServiceClient eventServiceClient;

    // admin endpoints
    @GetMapping("/admin/pending")
    public ResponseEntity<List<PayoutResponse>> getPendingPayouts() {
        return ResponseEntity.ok(payoutService.getPendingPayouts());
    }

    @GetMapping("/admin/all")
    public ResponseEntity<List<PayoutResponse>> getAllAdminPayouts() {
        return ResponseEntity.ok(payoutService.getAllAdminPayouts());
    }

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

    @PostMapping("/admin/{id}/approve")
    public ResponseEntity<PayoutResponse> approvePayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.approvePayout(id));
    }

    @PostMapping("/admin/{id}/dispute")
    public ResponseEntity<PayoutResponse> disputePayout(
            @PathVariable String id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(payoutService.disputePayout(id, reason));
    }

    @PostMapping("/admin/{id}/reopen")
    public ResponseEntity<PayoutResponse> reopenPayout(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.reopenPayout(id));
    }

    @PostMapping("/admin/{id}/reject")
    public ResponseEntity<PayoutResponse> rejectPayout(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(payoutService.rejectPayout(id, body == null ? null : body.get("reason")));
    }

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

    @PostMapping("/admin/{id}/start")
    public ResponseEntity<PayoutResponse> startDisbursement(@PathVariable String id) {
        return ResponseEntity.ok(payoutService.startDisbursement(id));
    }

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

    // organizer endpoints
    @GetMapping("/organizer")
    public ResponseEntity<List<PayoutResponse>> getOrganizerPayouts(@RequestHeader("Authorization") String authorization,
                                                                     @AuthenticationPrincipal Jwt jwt) {
        String organizerId = payoutService.resolveOrganizerId(authorization);
        return ResponseEntity.ok(payoutService.getOrganizerPayouts(organizerId));
    }

    @GetMapping("/organizer/{id}")
    public ResponseEntity<PayoutResponse> getOrganizerDetail(@RequestHeader("Authorization") String authorization, @PathVariable String id) {
        var response = payoutService.getOrganizerPayout(payoutService.resolveOrganizerId(authorization), id);
        // Event-name enrichment is ancillary: an event-service outage must not hide an
        // otherwise-owned payout request and its persisted status/amount/account snapshot.
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

    @PostMapping("/organizer/{id}/cancel")
    public ResponseEntity<PayoutResponse> cancelOrganizerPayout(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String authorization, @PathVariable String id) {
        return ResponseEntity.ok(payoutService.cancelPayout(payoutService.resolveOrganizerId(authorization), id));
    }

    @GetMapping("/organizer/requests/by-key/{key}")
    public ResponseEntity<PayoutResponse> getByIdempotencyKey(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Authorization") String authorization, @PathVariable String key) {
        return ResponseEntity.ok(payoutService.getByIdempotencyKey(payoutService.resolveOrganizerId(authorization), key));
    }

    @GetMapping("/organizer/summary")
    public ResponseEntity<com.haloticket.payment_service.dto.response.OrganizerFinanceResponse> getSummary(
            @RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization));
    }

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

    // Preserves direct Java callers while the optional query parameter remains backward compatible over HTTP.
    public ResponseEntity<com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse> getOrganizerAnalytics(
            String authorization, Jwt jwt, java.time.LocalDate from, java.time.LocalDate to) {
        return getOrganizerAnalytics(authorization, jwt, from, to, null);
    }

    @GetMapping("/organizer/events")
    public ResponseEntity<?> getOrganizerEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization).getItems());
    }

    @GetMapping("/organizer/eligible-events")
    public ResponseEntity<?> getEligibleEvents(@RequestHeader("Authorization") String authorization, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "FINAL_SETTLEMENT") String type) {
        if (!"FINAL_SETTLEMENT".equals(type)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported payout type");
        return ResponseEntity.ok(payoutService.getOrganizerFinance(payoutService.resolveOrganizerId(authorization), authorization).getItems().stream()
                .filter(item -> item.getEligibility().isEligible()).toList());
    }

    @PostMapping("/organizer/request")
    public ResponseEntity<PayoutResponse> requestPayout(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PayoutCreateRequest request) {
        return ResponseEntity.ok(payoutService.requestPayout(payoutService.resolveOrganizerId(authorization), authorization, request, idempotencyKey));
    }

    @PostMapping("/organizer/quote")
    public ResponseEntity<com.haloticket.payment_service.dto.response.PayoutQuoteResponse> quote(
            @RequestHeader("Authorization") String authorization, @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(payoutService.quotePayout(payoutService.resolveOrganizerId(authorization), authorization, request.eventId(), request.type(), request.requestedAmount()));
    }


    public record QuoteRequest(String eventId, String type, Long requestedAmount) {}
    public record AdminPayoutDetail(String id, String eventId, String eventName, String organizerUserId,
            com.haloticket.payment_service.enums.PayoutStatus status, String requestType, Double grossAmount,
            Double platformFee, Double netAmount, AccountSnapshot accountSnapshot, Boolean accountVerified,
            String publicReason, String transferReference, String receiptUrl, java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt, java.time.LocalDateTime transferredAt, List<String> allowedActions,
            List<PayoutResponse.TimelineEntry> timeline, Integer advanceRateSnapshot, Long policyVersionSnapshot,
            String policyScopeSnapshot, String availableAmountSnapshot, String advanceBaseSnapshot,
            String advanceLimitSnapshot, String paidAmountSnapshot, String reservedAmountSnapshot) {}
    public record AccountSnapshot(String accountHolder, String accountNumber, String bankName) {}

    public record AdminFinanceSummary(String collectedRevenue, String platformFees, String heldAmount,
            String processingAmount, String paidAmount, Map<String, Long> countsByStatus) {}
}
