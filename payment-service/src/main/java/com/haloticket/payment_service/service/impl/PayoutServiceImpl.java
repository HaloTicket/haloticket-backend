package com.haloticket.payment_service.service.impl;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.enums.PayoutStatus;
import com.haloticket.payment_service.repository.PayoutRepository;
import com.haloticket.payment_service.repository.PayoutReconciliationRepository;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.repository.PayoutQuoteRepository;
import com.haloticket.payment_service.client.EventServiceClient;
import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.client.BookingServiceClient;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import com.haloticket.payment_service.dto.response.EventFinanceSource;
import com.haloticket.payment_service.dto.response.BookingFinanceFacts;
import com.haloticket.payment_service.dto.response.OrganizerFinanceResponse;
import com.haloticket.payment_service.dto.response.PayoutQuoteResponse;
import com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse;
import com.haloticket.payment_service.entity.PayoutQuote;
import com.haloticket.payment_service.entity.PayoutReconciliation;
import com.haloticket.payment_service.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayoutServiceImpl implements PayoutService {

    private static final String WITHDRAWAL_TYPE = "FINAL_SETTLEMENT";
    private static final int PLATFORM_FEE_PERCENT = 5;

    private final PayoutRepository payoutRepository;
    private final PayoutReconciliationRepository reconciliationRepository;
    private final OrganizerPayoutAccountRepository accountRepository;
    private final EventServiceClient eventServiceClient;
    private final UserServiceClient userServiceClient;
    private final BookingServiceClient bookingServiceClient;
    private final OutboxEventRepository outboxEventRepository;
    private final PayoutQuoteRepository quoteRepository;

    @Override
    public String resolveOrganizerId(String authorization) {
        var identity = organizerIdentity(authorization);
        if (identity.userId() == null || identity.userId().isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Organizer identity is unavailable");
        return identity.userId();
    }

    private com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse organizerIdentity(String authorization) {
        try {
            var identity = userServiceClient.getOrganizerEligibility(authorization);
            if (identity == null) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Organizer identity is unavailable");
            return identity;
        } catch (org.springframework.web.server.ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Organizer identity lookup unavailable"); }
    }

    private boolean isVerifiedOrganizer(String organizerId, String authorization) {
        var identity = organizerIdentity(authorization);
        return identity.eligible() && organizerId.equals(identity.userId());
    }

    private void requireVerifiedOrganizer(String organizerId, String authorization) {
        if (!isVerifiedOrganizer(organizerId, authorization)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "KYC_REQUIRED");
    }

    @Override
    @Transactional
    public PayoutResponse requestPayout(String organizerId, String authorization, PayoutCreateRequest request, String idempotencyKey) {
        if (request == null || request.getEventId() == null || request.getType() == null || request.getQuoteId() == null ||
                request.getAccountVersion() == null || request.getRequestedAmount() == null || request.getRequestedAmount() <= 0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid payout request");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        requireVerifiedOrganizer(organizerId, authorization);
        var existing = payoutRepository.findByIdempotencyKeyForUpdate(idempotencyKey);
        if (existing.isPresent()) {
            Payout payout = existing.get();
            if (!organizerId.equals(payout.getOrganizerUserId()) || !request.getEventId().equals(payout.getEventId()) ||
                    !request.getType().equals(payout.getRequestType()) || !request.getQuoteId().equals(payout.getQuoteId()) ||
                    requestedAmount(payout) != request.getRequestedAmount())
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "Idempotency-Key was already used for a different request");
            return mapToResponse(payout);
        }
        requireWithdrawalType(request.getType());
        PayoutQuote quote = quoteRepository.findForUpdate(request.getQuoteId()).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Quote not found or expired"));
        if (!organizerId.equals(quote.getOrganizerUserId()) || !request.getEventId().equals(quote.getEventId()) ||
                !request.getType().equals(quote.getRequestType()) || !Boolean.FALSE.equals(quote.getConsumed()) ||
                quote.getExpiresAt().isBefore(java.time.LocalDateTime.now()) || !quote.getAccountVersion().equals(request.getAccountVersion()) ||
                !quote.getRequestAmount().equals(request.getRequestedAmount()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Quote is stale or does not match this request");
        var account = accountRepository.findForUpdate(organizerId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Verified payout account required"));
        if (!Boolean.TRUE.equals(account.getIsVerified()) || account.getAccountVersion() == null ||
                !account.getAccountVersion().equals(quote.getAccountVersion()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Payout account changed or is unverified");
        // The organizer account lock serializes withdrawals, including events with no historical reconciliation row.
        OrganizerFinanceResponse.EventFinanceItem current = getOrganizerFinance(organizerId, authorization).getItems().stream()
                .filter(i -> request.getEventId().equals(i.getEventId())).findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        if (!current.getEligibility().isEligible()
                || request.getRequestedAmount() != Long.parseLong(current.getAvailableAmount())
                || !quote.getGrossSnapshot().equals(Long.valueOf(current.getGrossCollected()))
                || !quote.getPlatformFeesSnapshot().equals(Long.valueOf(current.getPlatformFees())))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Available amount changed");
        Payout saved = payoutRepository.save(Payout.builder().eventId(request.getEventId()).organizerUserId(organizerId)
                .grossAmount(quote.getGrossSnapshot().doubleValue()).platformFee(quote.getPlatformFeesSnapshot().doubleValue())
                .netAmount(request.getRequestedAmount().doubleValue()).requestedAmountVnd(request.getRequestedAmount())
                .requestType(request.getType()).idempotencyKey(idempotencyKey).quoteId(quote.getId())
                .accountVersion(account.getAccountVersion()).accountHolderSnapshot(account.getAccountHolder())
                .accountNumberSnapshot(account.getAccountNumber()).bankNameSnapshot(account.getBankName())
                .advanceRateSnapshot(quote.getAdvanceRateSnapshot()).policyVersionSnapshot(quote.getPolicyVersionSnapshot())
                .policyScopeSnapshot(quote.getPolicyScopeSnapshot()).availableAmountSnapshot(quote.getAvailableAmount())
                .advanceBaseSnapshot(quote.getAdvanceBaseSnapshot()).advanceLimitSnapshot(quote.getAdvanceLimitSnapshot())
                .paidAmountSnapshot(quote.getPaidAmountSnapshot()).reservedAmountSnapshot(quote.getReservedAmountSnapshot())
                .status(PayoutStatus.PENDING).build());
        quote.setConsumed(true);
        quoteRepository.save(quote);
        return mapToResponse(saved);
    }

    @Override
    public OrganizerFinanceResponse getOrganizerFinance(String organizerId, String authorization) {
        List<EventFinanceSource> events = eventServiceClient.getMyEvents(authorization);
        boolean organizerVerified = isVerifiedOrganizer(organizerId, authorization);
        List<OrganizerFinanceResponse.EventFinanceItem> items = new ArrayList<>();
        for (EventFinanceSource event : events) {
            if (event.getOrganizer() == null || !organizerId.equals(event.getOrganizer().getUserId())) continue;
            PayoutReconciliation rec = reconciliationRepository.findByEventIdAndOrganizerUserId(event.getId(), organizerId).orElse(null);
            FinanceSource source = getFinanceSource(event.getId(), authorization);
            long gross = source.grossCollected();
            long fees = rec != null && rec.getConfirmedAt() != null && rec.getGrossCollected() == gross
                    ? rec.getPlatformFees() : Math.round(gross * (PLATFORM_FEE_PERCENT / 100.0));
            long refunds = source.refunds();
            long held = (rec == null ? 0 : rec.getHeldAmount()) + source.pendingRefunds();
            List<Payout> payouts = payoutRepository.findByOrganizerUserId(organizerId).stream()
                    .filter(p -> event.getId().equals(p.getEventId())).toList();
            long paid = payouts.stream().filter(p -> p.getStatus() == PayoutStatus.COMPLETED)
                    .mapToLong(this::requestedAmount).sum();
            long processing = payouts.stream().filter(p -> List.of(PayoutStatus.PENDING, PayoutStatus.APPROVED,
                    PayoutStatus.PROCESSING, PayoutStatus.NEEDS_VERIFICATION, PayoutStatus.DISPUTE).contains(p.getStatus()))
                    .mapToLong(this::requestedAmount).sum();
            boolean sourceMatchesBooking = gross == source.paidBookingAmount();
            boolean ended = event.getEndTime() != null && !event.getEndTime().isAfter(LocalDateTime.now());
            boolean cancelled = "CANCELLED".equals(event.getEventStatus());
            long available = ended && !cancelled && sourceMatchesBooking
                    ? Math.max(0, gross - fees - refunds - held - paid - processing) : 0;
            boolean accountVerified = accountRepository.findByOrganizerUserId(organizerId)
                    .map(a -> Boolean.TRUE.equals(a.getIsVerified()) && a.getVerificationReference() != null)
                    .orElse(false);
            List<String> reasons = new ArrayList<>();
            if (cancelled) reasons.add("EVENT_CANCELLED");
            else if (!ended) reasons.add("EVENT_NOT_ENDED");
            if (!sourceMatchesBooking) reasons.add("PAYMENT_BOOKING_MISMATCH");
            if (!accountVerified) reasons.add("PAYOUT_ACCOUNT_UNVERIFIED");
            if (!organizerVerified) reasons.add("IDENTITY_VERIFICATION_REQUIRED");
            if (available <= 0) reasons.add("NO_AVAILABLE_FUNDS");
            var eligibility = OrganizerFinanceResponse.Eligibility.builder().eligible(reasons.isEmpty()).reasonCodes(reasons).build();
            items.add(OrganizerFinanceResponse.EventFinanceItem.builder().eventId(event.getId()).title(event.getTitle())
                    .imageUrl(event.getBannerUrl() != null ? event.getBannerUrl() : event.getThumbnailUrl())
                    .endAt(event.getEndTime() == null ? null : event.getEndTime().toString())
                    .grossCollected(Long.toString(gross)).platformFees(Long.toString(fees)).refunds(Long.toString(refunds))
                    .heldAmount(Long.toString(held)).availableAmount(Long.toString(available)).paidAmount(Long.toString(paid))
                    .processingAmount(Long.toString(processing))
                    .reconciliationStatus(sourceMatchesBooking ? "CONFIRMED" : "SOURCE_MISMATCH")
                    .eligibility(eligibility)
                    .eligibilityByType(Map.of(WITHDRAWAL_TYPE, eligibility))
                    .maximumAdvanceAmount("0").build());
        }
        long gross = items.stream().mapToLong(i -> Long.parseLong(i.getGrossCollected())).sum();
        long fees = items.stream().mapToLong(i -> Long.parseLong(i.getPlatformFees())).sum();
        long refunds = items.stream().mapToLong(i -> Long.parseLong(i.getRefunds())).sum();
        long held = items.stream().mapToLong(i -> Long.parseLong(i.getHeldAmount())).sum();
        long available = items.stream().mapToLong(i -> Long.parseLong(i.getAvailableAmount())).sum();
        long paid = items.stream().mapToLong(i -> Long.parseLong(i.getPaidAmount())).sum();
        long processing = payoutRepository.findByOrganizerUserId(organizerId).stream().filter(p ->
                List.of(PayoutStatus.PENDING, PayoutStatus.APPROVED, PayoutStatus.PROCESSING, PayoutStatus.NEEDS_VERIFICATION, PayoutStatus.DISPUTE).contains(p.getStatus()))
                .mapToLong(this::requestedAmount).sum();
        return OrganizerFinanceResponse.builder().items(items).grossCollected(Long.toString(gross)).platformFees(Long.toString(fees))
                .refunds(Long.toString(refunds)).heldAmount(Long.toString(held)).availableAmount(Long.toString(available))
                .paidAmount(Long.toString(paid)).processingAmount(Long.toString(processing)).build();
    }

    @Override
    public OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to) {
        return getOrganizerAnalytics(organizerId, authorization, from, to, null);
    }

    @Override
    public OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to, String eventId) {
        if (from == null || to == null || to.isBefore(from)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "from and to are required and to must not precede from");
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Analytics range cannot exceed 366 days");

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toExclusive = to.plusDays(1).atStartOfDay();
        Map<LocalDate, long[]> daily = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) daily.put(day, new long[2]);

        List<EventFinanceSource> events;
        try {
            events = eventServiceClient.getMyEvents(authorization);
        } catch (Exception failure) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Organizer event source is unavailable");
        }
        if (events == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Organizer event source returned no ownership data");

        List<EventFinanceSource> selectedEvents = new ArrayList<>();
        boolean selectedEventOwned = false;
        for (EventFinanceSource event : events) {
            if (event == null || event.getId() == null || event.getOrganizer() == null ||
                    !organizerId.equals(event.getOrganizer().getUserId())) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Organizer event ownership facts are inconsistent");
            }
            if (eventId == null || eventId.isBlank() || eventId.equals(event.getId())) {
                selectedEvents.add(event);
                if (eventId != null && eventId.equals(event.getId())) selectedEventOwned = true;
            }
        }
        if (eventId != null && !eventId.isBlank() && !selectedEventOwned) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Organizer event not found");

        LinkedHashSet<String> bookingIds = new LinkedHashSet<>();
        for (EventFinanceSource event : selectedEvents) {
            BookingFinanceFacts facts;
            try {
                facts = bookingServiceClient.getFinanceFacts(event.getId(), authorization);
            } catch (Exception failure) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Organizer booking finance facts are unavailable");
            }
            if (facts == null || !event.getId().equals(facts.eventId()) || facts.paidBookingIds() == null) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Organizer booking ownership facts are inconsistent");
            }
            bookingIds.addAll(facts.paidBookingIds());
            if (bookingIds.size() > 100_000) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Organizer analytics exceeds the 100000-booking source limit");
        }

        long undatedRefunds = 0;
        List<String> ownedBookings = new ArrayList<>(bookingIds);
        for (int offset = 0; offset < ownedBookings.size(); offset += 10_000) {
            List<String> batch = ownedBookings.subList(offset, Math.min(offset + 10_000, ownedBookings.size()));
            List<OutboxEventRepository.DailyCashFlowFact> facts;
            try {
                facts = outboxEventRepository.findDailyCashFlowFactsByBookingIds(batch, fromDateTime, toExclusive);
            } catch (Exception failure) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Confirmed payment analytics are unavailable");
            }
            if (facts == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Confirmed payment analytics returned no result");
            for (OutboxEventRepository.DailyCashFlowFact fact : facts) {
                if (fact.getDate() == null) {
                    undatedRefunds += fact.getUndatedCompletedRefundCount() == null ? 0 : fact.getUndatedCompletedRefundCount();
                    continue;
                }
                long[] totals = daily.get(fact.getDate());
                if (totals == null) continue;
                totals[0] += fact.getGrossAmount() == null ? 0 : fact.getGrossAmount();
                totals[1] += fact.getRefundAmount() == null ? 0 : fact.getRefundAmount();
            }
        }

        List<OrganizerCashFlowResponse.DailyCashFlow> series = daily.entrySet().stream().map(entry ->
                OrganizerCashFlowResponse.DailyCashFlow.builder().date(entry.getKey())
                        .grossAmount(Long.toString(entry.getValue()[0])).refundAmount(Long.toString(entry.getValue()[1]))
                        .netCashFlow(Long.toString(entry.getValue()[0] - entry.getValue()[1])).build()).toList();
        long gross = daily.values().stream().mapToLong(values -> values[0]).sum();
        long refunds = daily.values().stream().mapToLong(values -> values[1]).sum();
        String refundNote = undatedRefunds > 0
                ? "No dated refund amount is included unless refunds.status is COMPLETED and refunds.updated_at is in range; " +
                        undatedRefunds + " completed refund row(s) without updated_at were found across the selected bookings."
                : "Refund amounts are included only when refunds.status is COMPLETED and refunds.updated_at falls in the selected dates; no undated completed refunds were found for the selected bookings.";
        return OrganizerCashFlowResponse.builder().from(from.toString()).to(to.toString()).series(series)
                .grossAmount(Long.toString(gross)).refundAmount(Long.toString(refunds)).netCashFlow(Long.toString(gross - refunds))
                .refundDataAvailable(refunds > 0)
                .undatedCompletedRefundCount(undatedRefunds)
                .refundDateNote(refundNote)
                .build();
    }

    @Override
    public PayoutQuoteResponse quotePayout(String organizerId, String authorization, String eventId, String type, Long requestedAmount) {
        requireWithdrawalType(type);
        OrganizerFinanceResponse.EventFinanceItem item = getOrganizerFinance(organizerId, authorization).getItems().stream()
                .filter(i -> eventId.equals(i.getEventId())).findFirst().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Event not found for organizer"));
        var account = accountRepository.findByOrganizerUserId(organizerId).orElse(null);
        List<String> reasons = new ArrayList<>(item.getEligibility().getReasonCodes());
        if (reasons.contains("IDENTITY_VERIFICATION_REQUIRED")) reasons.add("KYC_REQUIRED");
        long available = Long.parseLong(item.getAvailableAmount());
        long requested = requestedAmount == null ? available : requestedAmount;
        if (requested != available) reasons.add("FINAL_SETTLEMENT_MUST_USE_FULL_AVAILABLE_AMOUNT");
        return persistQuote(organizerId, eventId, type, requested, available, account, item, reasons, "Theo chính sách vận hành");
    }

    private void requireWithdrawalType(String type) {
        if (!WITHDRAWAL_TYPE.equals(type)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported payout type");
    }

    private PayoutQuoteResponse persistQuote(String userId, String eventId, String type, long requested, long maximum,
            com.haloticket.payment_service.entity.OrganizerPayoutAccount account,
            OrganizerFinanceResponse.EventFinanceItem item, List<String> reasons, String processingTime) {
        long version = account == null || account.getAccountVersion() == null ? 0L : account.getAccountVersion();
        boolean verified = account != null && Boolean.TRUE.equals(account.getIsVerified()) && account.getVerificationReference() != null;
        if (!verified && !reasons.contains("PAYOUT_ACCOUNT_UNVERIFIED")) reasons.add("PAYOUT_ACCOUNT_UNVERIFIED");
        boolean eligible = reasons.isEmpty();
        var expires = java.time.LocalDateTime.now().plusMinutes(10);
        String id = java.util.UUID.randomUUID().toString();
        if (eligible) quoteRepository.save(PayoutQuote.builder().id(id).organizerUserId(userId).eventId(eventId).requestType(type)
                .requestAmount(requested).availableAmount(Long.parseLong(item.getAvailableAmount()))
                .grossSnapshot(Long.parseLong(item.getGrossCollected())).platformFeesSnapshot(Long.parseLong(item.getPlatformFees()))
                .advanceRateSnapshot(0).policyVersionSnapshot(0L).policyScopeSnapshot("GLOBAL")
                .advanceBaseSnapshot(0L).advanceLimitSnapshot(0L)
                .paidAmountSnapshot(Long.parseLong(item.getPaidAmount())).reservedAmountSnapshot(Long.parseLong(item.getProcessingAmount()))
                .accountVersion(version).expiresAt(expires).consumed(false).build());
        return PayoutQuoteResponse.builder().quoteId(eligible ? id : null).eventId(eventId).type(type).expiresAt(expires)
                .accountVersion(version).grossAmount(item.getGrossCollected()).deductions(Long.toString(Long.parseLong(item.getPlatformFees())
                        + Long.parseLong(item.getRefunds()) + Long.parseLong(item.getHeldAmount())))
                .availableAmount(item.getAvailableAmount()).maximumAmount(Long.toString(maximum)).payoutFee("0")
                .expectedTransferAmount(Long.toString(Math.max(0, requested))).eligible(eligible).reasonCodes(reasons)
                .processingTime(processingTime).account(account == null ? null : PayoutQuoteResponse.Account.builder()
                        .accountHolder(account.getAccountHolder()).maskedAccountNumber(mask(account.getAccountNumber()))
                        .bankName(account.getBankName()).build()).build();
    }

    @Override
    public PayoutService.FinanceSource getFinanceSource(String eventId, String authorization) {
        try {
            var bookings = bookingServiceClient.getFinanceFacts(eventId, authorization);
            if (bookings != null && !eventId.equals(bookings.eventId())) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Event payment source is inconsistent");
            if (bookings == null || bookings.paidBookingIds() == null || bookings.paidBookingIds().isEmpty()) return new PayoutService.FinanceSource(0, 0, 0, 0);
            if (bookings.paidBookingIds().size() > 100_000) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Event payment source exceeds supported limit");
            var facts = outboxEventRepository.findSuccessfulPaymentFactsByBookingIds(bookings.paidBookingIds(),
                    java.time.LocalDateTime.of(2000, 1, 1, 0, 0), java.time.LocalDateTime.now().plusDays(1));
            long gross = facts.stream().mapToLong(f -> f.getRevenue() == null ? 0L : Math.round(f.getRevenue())).sum();
            long refunds = facts.stream().mapToLong(f -> f.getRefunds() == null ? 0L : Math.round(f.getRefunds())).sum();
            long pendingRefunds = facts.stream().mapToLong(f -> f.getPendingRefunds() == null ? 0L : Math.round(f.getPendingRefunds())).sum();
            return new PayoutService.FinanceSource(gross, refunds, bookings.paidBookingAmount(), pendingRefunds);
        } catch (org.springframework.web.server.ResponseStatusException exception) { throw exception; }
        catch (Exception exception) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Payment source unavailable"); }
    }

    private String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return "••••";
        return "••••" + accountNumber.substring(accountNumber.length() - 4);
    }

    private long requestedAmount(Payout payout) {
        if (payout.getRequestedAmountVnd() != null) return payout.getRequestedAmountVnd();
        return payout.getNetAmount() == null ? 0L : Math.round(payout.getNetAmount());
    }
    private String asString(Long amount) { return amount == null ? null : Long.toString(amount); }

    @Override
    public PayoutResponse getOrganizerPayout(String organizerId, String payoutId) {
        Payout payout = payoutRepository.findById(payoutId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        if (!organizerId.equals(payout.getOrganizerUserId())) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
        return mapToResponse(payout);
    }

    @Override
    public PayoutResponse getByIdempotencyKey(String organizerId, String key) {
        Payout payout = payoutRepository.findByIdempotencyKey(key).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        if (!organizerId.equals(payout.getOrganizerUserId())) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
        return mapToResponse(payout);
    }

    @Override
    @Transactional
    public PayoutResponse cancelPayout(String organizerId, String payoutId) {
        Payout payout = payoutRepository.findForUpdate(payoutId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        if (!organizerId.equals(payout.getOrganizerUserId())) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND);
        requireStatus(payout, PayoutStatus.PENDING);
        payout.setStatus(PayoutStatus.CANCELLED);
        return mapToResponse(payoutRepository.save(payout));
    }

    @Override
    @Transactional
    public PayoutResponse startDisbursement(String payoutId) {
        Payout payout = payoutRepository.findForUpdate(payoutId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        requireStatus(payout, PayoutStatus.APPROVED);
        payout.setStatus(PayoutStatus.PROCESSING);
        payout.setProcessingAt(java.time.LocalDateTime.now());
        return mapToResponse(payoutRepository.save(payout));
    }

    @Override
    @Transactional
    public PayoutResponse completeDisbursement(String payoutId, String reference, java.time.LocalDateTime transferredAt, String receiptUrl) {
        Payout payout = payoutRepository.findForUpdate(payoutId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        if (payout.getStatus() != PayoutStatus.APPROVED) requireStatus(payout, PayoutStatus.PROCESSING);
        if (reference == null || reference.isBlank() || transferredAt == null || transferredAt.isAfter(java.time.LocalDateTime.now()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Valid transfer reference and past transfer time are required");
        payout.setTransferReference(reference);
        payout.setTransferReceiptUrl(receiptUrl);
        payout.setStatus(PayoutStatus.COMPLETED);
        payout.setTransferredAt(transferredAt);
        return mapToResponse(payoutRepository.save(payout));
    }

    @Override
    @Transactional
    public PayoutResponse approvePayout(String payoutId) {
        Payout payout = payoutRepository.findForUpdate(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        requireStatus(payout, PayoutStatus.PENDING);
        payout.setStatus(PayoutStatus.APPROVED);
        payout.setApprovedAt(java.time.LocalDateTime.now());
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public PayoutResponse disputePayout(String payoutId, String reason) {
        Payout payout = payoutRepository.findForUpdate(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        requireStatus(payout, PayoutStatus.PENDING);
        if (reason == null || reason.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Dispute reason is required");
        payout.setPublicReason(reason.trim());
        payout.setStatus(PayoutStatus.DISPUTE);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public PayoutResponse reopenPayout(String payoutId) {
        Payout payout = payoutRepository.findForUpdate(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        requireStatus(payout, PayoutStatus.DISPUTE);
        payout.setStatus(PayoutStatus.PENDING);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public PayoutResponse rejectPayout(String payoutId, String reason) {
        Payout payout = payoutRepository.findForUpdate(payoutId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Payout not found with id: " + payoutId));
        requireStatus(payout, PayoutStatus.PENDING);
        if (reason == null || reason.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Rejection reason is required");
        payout.setPublicReason(reason.trim());
        payout.setStatus(PayoutStatus.REJECTED);
        Payout saved = payoutRepository.save(payout);
        return mapToResponse(saved);
    }

    @Override
    public List<PayoutResponse> getPendingPayouts() {
        return payoutRepository.findByStatus(PayoutStatus.PENDING)
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    public List<PayoutResponse> getAllAdminPayouts() {
        Map<String, String> eventNames;
        try {
            eventNames = eventServiceClient.getAllEvents().stream()
                    .filter(event -> event.getId() != null)
                    .collect(Collectors.toMap(EventFinanceSource::getId, EventFinanceSource::getTitle, (first, ignored) -> first));
        } catch (Exception ignored) {
            eventNames = Map.of();
        }
        final Map<String, String> resolvedEventNames = eventNames;
        return payoutRepository.findAll().stream()
                .map(payout -> {
                    PayoutResponse response = mapToResponse(payout);
                    response.setEventName(resolvedEventNames.get(payout.getEventId()));
                    return response;
                }).collect(Collectors.toList());
    }

    @Override
    public List<PayoutResponse> getOrganizerPayouts(String organizerId) {
        return payoutRepository.findByOrganizerUserId(organizerId)
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private PayoutResponse mapToResponse(Payout p) {
        return PayoutResponse.builder()
                .id(p.getId())
                .eventId(p.getEventId())
                .organizerUserId(p.getOrganizerUserId())
                .grossAmount(p.getGrossAmount())
                .platformFee(p.getPlatformFee())
                .netAmount(p.getNetAmount())
                .status(p.getStatus())
                .transferredAt(p.getTransferredAt())
                .approvedAt(p.getApprovedAt())
                .processingAt(p.getProcessingAt())
                .advanceRateSnapshot(p.getAdvanceRateSnapshot()).policyVersionSnapshot(p.getPolicyVersionSnapshot())
                .policyScopeSnapshot(p.getPolicyScopeSnapshot())
                .availableAmountSnapshot(asString(p.getAvailableAmountSnapshot())).advanceBaseSnapshot(asString(p.getAdvanceBaseSnapshot()))
                .advanceLimitSnapshot(asString(p.getAdvanceLimitSnapshot())).paidAmountSnapshot(asString(p.getPaidAmountSnapshot()))
                .reservedAmountSnapshot(asString(p.getReservedAmountSnapshot()))
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .requestType(p.getRequestType() == null || p.getRequestType().isBlank() ? "LEGACY" : p.getRequestType())
                .requestedAmount(Long.toString(requestedAmount(p)))
                .transferReference(p.getTransferReference())
                .accountHolderSnapshot(p.getAccountHolderSnapshot())
                .maskedAccountNumber(mask(p.getAccountNumberSnapshot()))
                .bankNameSnapshot(p.getBankNameSnapshot())
                .accountSnapshot(p.getBankNameSnapshot() == null && p.getAccountHolderSnapshot() == null && p.getAccountNumberSnapshot() == null
                        ? null : PayoutResponse.AccountSnapshot.builder().bankName(p.getBankNameSnapshot())
                        .accountHolder(p.getAccountHolderSnapshot()).accountNumberMasked(mask(p.getAccountNumberSnapshot())).build())
                .canCancel(p.getStatus() == PayoutStatus.PENDING)
                .publicReason(p.getPublicReason())
                .quoteId(p.getQuoteId())
                .receiptUrl(p.getTransferReceiptUrl())
                .timeline(timeline(p))
                .build();
    }

    private List<PayoutResponse.TimelineEntry> timeline(Payout payout) {
        List<PayoutResponse.TimelineEntry> entries = new ArrayList<>();
        if (payout.getCreatedAt() != null) entries.add(timelineEntry("PENDING", payout.getCreatedAt(), null));
        if (payout.getApprovedAt() != null) entries.add(timelineEntry("APPROVED", payout.getApprovedAt(), null));
        if (payout.getProcessingAt() != null) entries.add(timelineEntry("PROCESSING", payout.getProcessingAt(), null));
        if (payout.getStatus() == PayoutStatus.REJECTED || payout.getStatus() == PayoutStatus.DISPUTE || payout.getStatus() == PayoutStatus.CANCELLED)
            entries.add(timelineEntry(payout.getStatus().name(), payout.getUpdatedAt(), payout.getPublicReason()));
        if (payout.getTransferredAt() != null) entries.add(timelineEntry("COMPLETED", payout.getTransferredAt(), null));
        boolean represented = entries.stream().anyMatch(entry -> entry.getStatus().equals(payout.getStatus().name()));
        if (!represented && payout.getStatus() != PayoutStatus.PENDING && payout.getUpdatedAt() != null)
            entries.add(timelineEntry(payout.getStatus().name(), payout.getUpdatedAt(), payout.getPublicReason()));
        return entries;
    }

    private PayoutResponse.TimelineEntry timelineEntry(String status, LocalDateTime at, String reason) {
        return PayoutResponse.TimelineEntry.builder().status(status).at(at).reason(reason)
                .occurredAt(at).publicMessage(reason).build();
    }

    private void requireStatus(Payout payout, PayoutStatus expected) {
        if (payout.getStatus() != expected) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Payout status changed; reload and review the request");
        }
    }
}
