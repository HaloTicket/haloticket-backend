package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.entity.PayoutQuote;
import com.haloticket.payment_service.entity.PayoutReconciliation;
import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import com.haloticket.payment_service.dto.response.EventFinanceSource;
import com.haloticket.payment_service.dto.response.PayoutResponse;
import com.haloticket.payment_service.dto.response.PayoutQuoteResponse;
import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.payment_service.dto.response.BookingFinanceFacts;
import com.haloticket.payment_service.enums.PayoutStatus;
import com.haloticket.payment_service.repository.PayoutRepository;
import com.haloticket.payment_service.repository.PayoutQuoteRepository;
import com.haloticket.payment_service.repository.PayoutReconciliationRepository;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.client.EventServiceClient;
import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.client.BookingServiceClient;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import com.haloticket.payment_service.service.impl.PayoutServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutServiceImplTest {
    private final AtomicInteger repositoryCalls = new AtomicInteger();
    private Payout payout;
    private final PayoutRepository payoutRepository = (PayoutRepository) Proxy.newProxyInstance(
            PayoutRepository.class.getClassLoader(), new Class<?>[]{PayoutRepository.class}, (proxy, method, args) -> {
                repositoryCalls.incrementAndGet();
                return switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(payout);
                    case "findForUpdate" -> Optional.ofNullable(payout);
                    case "findByIdempotencyKeyForUpdate" -> Optional.empty();
                    case "save" -> { payout = (Payout) args[0]; yield payout; }
                    case "findByStatus", "findByOrganizerUserId", "findAll" -> List.of();
                    case "toString" -> "PayoutRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });

    private PayoutServiceImpl service() {
        return new PayoutServiceImpl(payoutRepository,
                proxy(PayoutReconciliationRepository.class), proxy(OrganizerPayoutAccountRepository.class),
                proxy(EventServiceClient.class), proxy(UserServiceClient.class), proxy(BookingServiceClient.class),
                proxy(OutboxEventRepository.class), proxy(PayoutQuoteRepository.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> {
            if (method.getName().equals("getMyEvents")) return List.of();
            if (method.getName().equals("findByEventIdAndOrganizerUserId") || method.getName().equals("findByOrganizerUserId") || method.getName().equals("findById")) return Optional.empty();
            if (method.getName().equals("findAllByOrganizerUserId")) return List.of();
            if (method.getName().equals("save")) return args[0];
            throw new UnsupportedOperationException(method.getName());
        });
    }

    @Test
    void organizerCannotCreatePayoutFromClientSuppliedAmounts() {
        PayoutCreateRequest request = new PayoutCreateRequest();
        request.setEventId("event-1");
        request.setType("FINAL_SETTLEMENT");
        request.setQuoteId("quote-1");
        request.setAccountVersion(1L);
        request.setRequestedAmount(999999L);
        request.setRequestAmount(999999D);
        request.setPlatformFeeRate(0D);

        assertThatThrownBy(() -> service().requestPayout("organizer-1", "Bearer user-token", request, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThat(repositoryCalls).hasValue(0);
    }

    @Test
    void approvalDoesNotMarkPayoutAsTransferred() {
        payout = Payout.builder().id("payout-1").status(PayoutStatus.PENDING).build();

        var response = service().approvePayout("payout-1");

        assertThat(response.getStatus()).isEqualTo(PayoutStatus.APPROVED);
        assertThat(response.getTransferredAt()).isNull();
    }

    @Test
    void rejectionIsSeparateFromTransferFailure() {
        payout = Payout.builder().id("payout-1").status(PayoutStatus.PENDING).build();

        var response = service().rejectPayout("payout-1", "Rejected for review");

        assertThat(response.getStatus()).isEqualTo(PayoutStatus.REJECTED);
    }

    @Test
    void approvalRejectsStaleStatus() {
        payout = Payout.builder().id("payout-1").status(PayoutStatus.APPROVED).build();

        assertThatThrownBy(() -> service().approvePayout("payout-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("status changed");
        assertThat(repositoryCalls).hasValue(1);
    }

    @Test
    void quoteRequestIsIdempotentAndStoresAccountSnapshot() {
        var fixture = financeFixture();
        PayoutQuoteResponse quote = fixture.service.quotePayout("user-1", "Bearer token", "event-1", "FINAL_SETTLEMENT", null);
        assertThat(quote.isEligible()).isTrue();
        PayoutCreateRequest request = request(quote, "FINAL_SETTLEMENT", 1000L);

        PayoutResponse first = fixture.service.requestPayout("user-1", "Bearer token", request, "key-1");
        PayoutResponse retry = fixture.service.requestPayout("user-1", "Bearer token", request, "key-1");

        assertThat(first.getId()).isEqualTo(retry.getId());
        assertThat(fixture.payouts).hasSize(1);
        assertThat(fixture.payouts.getFirst().getAccountNumberSnapshot()).isEqualTo("1234567890");
        request.setRequestedAmount(999L);
        assertThatThrownBy(() -> fixture.service.requestPayout("user-1", "Bearer token", request, "key-1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("different request");
    }

    @Test
    void staleQuoteCannotReserveMoreThanCurrentBalance() {
        var fixture = financeFixture();
        PayoutQuoteResponse quote = fixture.service.quotePayout("user-1", "Bearer token", "event-1", "FINAL_SETTLEMENT", null);
        fixture.reconciliation.setHeldAmount(500L);

        assertThatThrownBy(() -> fixture.service.requestPayout("user-1", "Bearer token",
                request(quote, "FINAL_SETTLEMENT", 1000L), "key-1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Available amount changed");
        assertThat(fixture.payouts).isEmpty();
    }

    @Test
    void endedEventCanWithdrawWithoutManualReconciliation() {
        var fixture = financeFixture();
        fixture.reconciliation.setConfirmedAt(null);
        var quote = fixture.service.quotePayout("user-1", "Bearer token", "event-1", "FINAL_SETTLEMENT", null);
        assertThat(quote.isEligible()).isTrue();
        assertThat(quote.getAvailableAmount()).isEqualTo("950");
        var response = fixture.service.requestPayout("user-1", "Bearer token",
                request(quote, "FINAL_SETTLEMENT", 950L), "withdrawal-1");
        assertThat(response.getStatus()).isEqualTo(PayoutStatus.PENDING);
        var nextQuote = fixture.service.quotePayout("user-1", "Bearer token", "event-1", "FINAL_SETTLEMENT", null);
        assertThat(nextQuote.isEligible()).isFalse();
    }

    @Test
    void advanceQuotesAndNewAdvanceRequestsAreDisabled() {
        var fixture = financeFixture();
        assertThatThrownBy(() -> fixture.service.quotePayout("user-1", "Bearer token", "event-1", "ADVANCE", 100L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unsupported payout type");
        var quote = fixture.service.quotePayout("user-1", "Bearer token", "event-1", "FINAL_SETTLEMENT", null);
        assertThatThrownBy(() -> fixture.service.requestPayout("user-1", "Bearer token",
                request(quote, "ADVANCE", 100L), "advance-1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Unsupported payout type");
    }

    @Test
    void approvedDisbursementCanBeCompletedDirectlyWithBankEvidence() {
        Payout payout = Payout.builder().id("payout-1").status(PayoutStatus.PENDING).build();
        whenFindPayout(payout);
        LocalDateTime transferredAt = LocalDateTime.now().minusMinutes(1);
        assertThatThrownBy(() -> service().completeDisbursement("payout-1", "bank-ref", transferredAt, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("status changed");
        payout.setStatus(PayoutStatus.APPROVED);
        assertThatThrownBy(() -> service().completeDisbursement("payout-1", "", transferredAt, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Valid transfer reference");
        var completed = service().completeDisbursement("payout-1", "bank-ref", transferredAt, null);
        assertThat(completed.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(completed.getTransferredAt()).isEqualTo(transferredAt);
    }

    @Test
    void organizerCannotReadAnotherOwnersPayout() {
        payout = Payout.builder().id("payout-1").organizerUserId("another-user").status(PayoutStatus.PENDING).build();
        assertThatThrownBy(() -> service().getOrganizerPayout("user-1", "payout-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void payoutDetailExposesMaskedAccountAndOnlyPersistedLifecycleTimes() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 30, 21, 20);
        LocalDateTime approved = created.plusMinutes(5);
        LocalDateTime processing = approved.plusMinutes(2);
        payout = Payout.builder().id("payout-1").organizerUserId("user-1").eventId("event-1")
                .status(PayoutStatus.PROCESSING).createdAt(created).approvedAt(approved).processingAt(processing)
                .accountHolderSnapshot("Account Holder").accountNumberSnapshot("1234567890").bankNameSnapshot("Test Bank")
                .grossAmount(2000D).platformFee(100D).netAmount(1900D).build();

        PayoutResponse detail = service().getOrganizerPayout("user-1", "payout-1");

        assertThat(detail.getAccountSnapshot().getAccountNumberMasked()).isEqualTo("••••7890");
        assertThat(detail.getAccountSnapshot().getAccountNumberMasked()).doesNotContain("123456");
        assertThat(detail.getGrossAmount()).isEqualTo(2000D);
        assertThat(detail.getPlatformFee()).isEqualTo(100D);
        assertThat(detail.getNetAmount()).isEqualTo(1900D);
        assertThat(detail.getTimeline()).extracting(PayoutResponse.TimelineEntry::getStatus)
                .containsExactly("PENDING", "APPROVED", "PROCESSING");
        assertThat(detail.getTimeline()).extracting(PayoutResponse.TimelineEntry::getAt)
                .containsExactly(created, approved, processing);
        assertThat(detail.getTimeline()).extracting(PayoutResponse.TimelineEntry::getOccurredAt)
                .containsExactly(created, approved, processing);
    }

    @Test
    void organizerAnalyticsUsesOwnedBookingsAndInclusiveCalendarRange() {
        AtomicReference<LocalDateTime[]> requestedRange = new AtomicReference<>();
        AtomicReference<List<String>> requestedBookings = new AtomicReference<>();
        OutboxEventRepository datedFacts = proxy(OutboxEventRepository.class, (method, args) -> {
            if (method.equals("findDailyCashFlowFactsByBookingIds")) {
                requestedBookings.set((List<String>) args[0]);
                requestedRange.set(new LocalDateTime[]{(LocalDateTime) args[1], (LocalDateTime) args[2]});
                Class<?> projectionType = Class.forName("com.haloticket.payment_service.repository.OutboxEventRepository$DailyCashFlowFact");
                return List.of(dailyFact(projectionType, LocalDate.of(2026, 10, 2), 12500L, 750L, 0L),
                        dailyFact(projectionType, null, 0L, 0L, 2L));
            }
            return List.of();
        });
        var fixture = financeFixture(datedFacts);

        var response = fixture.service.getOrganizerAnalytics("user-1", "Bearer organizer-token",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));

        assertThat(requestedRange.get()).containsExactly(LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 4, 0, 0));
        assertThat(requestedBookings.get()).containsExactly("booking-1");
        assertThat(response.getSeries()).hasSize(3);
        assertThat(response.getSeries().get(1).getDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(response.getSeries().get(1).getGrossAmount()).isEqualTo("12500");
        assertThat(response.getSeries().get(1).getRefundAmount()).isEqualTo("750");
        assertThat(response.getSeries().get(0).getGrossAmount()).isEqualTo("0");
        assertThat(response.isRefundDataAvailable()).isTrue();
        assertThat(response.getUndatedCompletedRefundCount()).isEqualTo(2L);
    }

    @Test
    void organizerAnalyticsRejectsDatesOutsideSupportedRange() {
        var fixture = financeFixture();

        assertThatThrownBy(() -> fixture.service.getOrganizerAnalytics("user-1", "Bearer organizer-token",
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 3)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("366 days");
    }

    @Test
    void organizerAnalyticsRejectsEventOwnershipMismatchBeforeReadingBookings() {
        EventFinanceSource foreignEvent = new EventFinanceSource();
        foreignEvent.setId("foreign-event");
        EventFinanceSource.Organizer owner = new EventFinanceSource.Organizer();
        owner.setUserId("different-user");
        foreignEvent.setOrganizer(owner);
        EventServiceClient events = proxy(EventServiceClient.class, (method, args) -> List.of(foreignEvent));
        AtomicInteger bookingCalls = new AtomicInteger();
        BookingServiceClient bookings = proxy(BookingServiceClient.class, (method, args) -> {
            bookingCalls.incrementAndGet();
            return new BookingFinanceFacts("foreign-event", List.of("booking-1"), 1000L);
        });
        PayoutServiceImpl analytics = new PayoutServiceImpl(payoutRepository,
                proxy(PayoutReconciliationRepository.class), proxy(OrganizerPayoutAccountRepository.class),
                events, proxy(UserServiceClient.class), bookings, proxy(OutboxEventRepository.class),
                proxy(PayoutQuoteRepository.class));

        assertThatThrownBy(() -> analytics.getOrganizerAnalytics("user-1", "Bearer token",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ownership facts are inconsistent");
        assertThat(bookingCalls).hasValue(0);
    }

    @Test
    void eventFilteredAnalyticsOnlyQueriesSelectedOwnedEvents() {
        AtomicReference<List<String>> requestedBookings = new AtomicReference<>();
        AtomicInteger bookingCalls = new AtomicInteger();
        EventServiceClient events = proxy(EventServiceClient.class, (method, args) -> List.of(
                ownedEvent("event-a", "user-1"), ownedEvent("event-b", "user-1")));
        BookingServiceClient bookings = proxy(BookingServiceClient.class, (method, args) -> {
            bookingCalls.incrementAndGet();
            String selectedEvent = (String) args[0];
            return new BookingFinanceFacts(selectedEvent, List.of("booking-" + selectedEvent), 1000L);
        });
        OutboxEventRepository daily = proxy(OutboxEventRepository.class, (method, args) -> {
            if (method.equals("findDailyCashFlowFactsByBookingIds")) {
                requestedBookings.set((List<String>) args[0]);
                Class<?> projection = Class.forName("com.haloticket.payment_service.repository.OutboxEventRepository$DailyCashFlowFact");
                return List.of(dailyFact(projection, LocalDate.of(2026, 10, 2), 1000L, 0L, 0L));
            }
            return List.of();
        });
        PayoutServiceImpl analytics = analyticsService(events, bookings, daily);

        var result = analytics.getOrganizerAnalytics("user-1", "Bearer token",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "event-b");

        assertThat(bookingCalls).hasValue(1);
        assertThat(requestedBookings.get()).containsExactly("booking-event-b");
        assertThat(result.getGrossAmount()).isEqualTo("1000");
    }

    @Test
    void eventFilteredAnalyticsRejectsForeignEventBeforeBookingLookup() {
        AtomicInteger bookingCalls = new AtomicInteger();
        EventServiceClient events = proxy(EventServiceClient.class, (method, args) -> List.of(ownedEvent("event-a", "user-1")));
        BookingServiceClient bookings = proxy(BookingServiceClient.class, (method, args) -> {
            bookingCalls.incrementAndGet();
            return new BookingFinanceFacts((String) args[0], List.of("booking-1"), 1000L);
        });
        PayoutServiceImpl analytics = analyticsService(events, bookings, proxy(OutboxEventRepository.class, (method, args) -> List.of()));

        assertThatThrownBy(() -> analytics.getOrganizerAnalytics("user-1", "Bearer token",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), "foreign-event"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404 NOT_FOUND");
        assertThat(bookingCalls).hasValue(0);
    }

    private static EventFinanceSource ownedEvent(String id, String organizerId) {
        EventFinanceSource event = new EventFinanceSource(); event.setId(id);
        EventFinanceSource.Organizer organizer = new EventFinanceSource.Organizer(); organizer.setUserId(organizerId);
        event.setOrganizer(organizer); return event;
    }

    private PayoutServiceImpl analyticsService(EventServiceClient events, BookingServiceClient bookings, OutboxEventRepository outbox) {
        return new PayoutServiceImpl(proxy(PayoutRepository.class), proxy(PayoutReconciliationRepository.class),
                proxy(OrganizerPayoutAccountRepository.class), events, proxy(UserServiceClient.class), bookings, outbox,
                proxy(PayoutQuoteRepository.class));
    }

    @Test
    void unverifiedOrganizerCanReadOwnFinanceAndSeesKycEligibilityReason() {
        var fixture = financeFixture(null, false);

        String organizerId = fixture.service.resolveOrganizerId("Bearer organizer-token");
        var finance = fixture.service.getOrganizerFinance(organizerId, "Bearer organizer-token");

        assertThat(organizerId).isEqualTo("user-1");
        assertThat(finance.getItems()).hasSize(1);
        assertThat(finance.getItems().getFirst().getEligibility().getReasonCodes()).contains("IDENTITY_VERIFICATION_REQUIRED");
    }

    @Test
    void unverifiedOrganizerReceivesIneligibleQuoteWithKycReason() {
        var fixture = financeFixture(null, false);

        var quote = fixture.service.quotePayout("user-1", "Bearer organizer-token", "event-1", "FINAL_SETTLEMENT", null);

        assertThat(quote.isEligible()).isFalse();
        assertThat(quote.getReasonCodes()).contains("KYC_REQUIRED");
        assertThat(quote.getQuoteId()).isNull();
    }

    @Test
    void requestRechecksKycEligibilityAfterQuoteWasCreated() {
        var fixture = financeFixture();
        var quote = fixture.service.quotePayout("user-1", "Bearer organizer-token", "event-1", "FINAL_SETTLEMENT", null);
        var request = request(quote, "FINAL_SETTLEMENT", 1000L);
        fixture.kycEligible.set(false);

        assertThatThrownBy(() -> fixture.service.requestPayout("user-1", "Bearer organizer-token", request, "kyc-key"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("KYC_REQUIRED");
        assertThat(fixture.payouts).isEmpty();
    }

    private static Object dailyFact(Class<?> projectionType, LocalDate date, Long gross, Long refunds, Long undated) {
        return Proxy.newProxyInstance(projectionType.getClassLoader(), new Class<?>[]{projectionType}, (p, method, args) -> switch (method.getName()) {
            case "getDate" -> date;
            case "getGrossAmount" -> gross;
            case "getRefundAmount" -> refunds;
            case "getUndatedCompletedRefundCount" -> undated;
            default -> null;
        });
    }

    private void whenFindPayout(Payout payout) {
        this.payout = payout;
    }

    private static PayoutCreateRequest request(PayoutQuoteResponse quote, String type, Long amount) {
        PayoutCreateRequest request = new PayoutCreateRequest();
        request.setEventId(quote.getEventId()); request.setType(type); request.setQuoteId(quote.getQuoteId());
        request.setAccountVersion(quote.getAccountVersion()); request.setRequestedAmount(amount);
        return request;
    }

    private Fixture financeFixture() { return financeFixture(null, true); }

    private Fixture financeFixture(OutboxEventRepository customPayments) { return financeFixture(customPayments, true); }

    private Fixture financeFixture(OutboxEventRepository customPayments, boolean eligible) {
        var rec = PayoutReconciliation.builder().eventId("event-1").organizerUserId("user-1").grossCollected(1000L)
                .platformFees(0L).refunds(0L).heldAmount(0L).confirmedAt(LocalDateTime.now().minusDays(1)).build();
        var account = OrganizerPayoutAccount.builder().organizerUserId("user-1").accountHolder("Test User")
                .accountNumber("1234567890").bankName("Test Bank").isVerified(true).verificationReference("verified")
                .accountVersion(1L).build();
        var event = new EventFinanceSource(); event.setId("event-1"); event.setTitle("Test Event");
        event.setEndTime(LocalDateTime.now().minusDays(1));
        var owner = new EventFinanceSource.Organizer(); owner.setUserId("user-1"); event.setOrganizer(owner);
        List<Payout> savedPayouts = new ArrayList<>(); HashMap<String, PayoutQuote> quotes = new HashMap<>();
        PayoutRepository payoutRepo = proxy(PayoutRepository.class, (method, args) -> switch (method) {
            case "findByIdempotencyKey", "findByIdempotencyKeyForUpdate" -> savedPayouts.stream().filter(p -> args[0].equals(p.getIdempotencyKey())).findFirst();
            case "findById", "findForUpdate" -> savedPayouts.stream().filter(p -> args[0].equals(p.getId())).findFirst().or(() -> Optional.ofNullable(payout));
            case "findByOrganizerUserId" -> new ArrayList<>(savedPayouts);
            case "findByStatus", "findAll" -> List.of();
            case "save" -> { Payout p = (Payout) args[0]; if (p.getId() == null) p.setId("payout-" + (savedPayouts.size()+1)); if (!savedPayouts.contains(p)) savedPayouts.add(p); yield p; }
            default -> throw new UnsupportedOperationException(method);
        });
        PayoutReconciliationRepository recRepo = proxy(PayoutReconciliationRepository.class, (method, args) -> switch (method) {
            case "findByEventIdAndOrganizerUserId" -> rec.getConfirmedAt() == null ? Optional.empty() : Optional.of(rec);
            case "findAll" -> List.of(rec);
            default -> throw new UnsupportedOperationException(method);
        });
        OrganizerPayoutAccountRepository accountRepo = proxy(OrganizerPayoutAccountRepository.class, (method, args) -> Optional.of(account));
        EventServiceClient events = proxy(EventServiceClient.class, (method, args) -> List.of(event));
        AtomicBoolean kycEligible = new AtomicBoolean(eligible);
        UserServiceClient users = proxy(UserServiceClient.class, (method, args) -> new OrganizerEligibilityResponse(
                kycEligible.get(), "user-1", kycEligible.get() ? 1L : null, kycEligible.get() ? null : "EKYC_NOT_STARTED"));
        BookingServiceClient bookings = proxy(BookingServiceClient.class, (method, args) -> new BookingFinanceFacts("event-1", List.of("booking-1"), 1000L));
        OutboxEventRepository payments = customPayments == null ? proxy(OutboxEventRepository.class, (method, args) -> List.of(new OutboxEventRepository.SuccessfulPaymentFact() {
            public String getBookingId() { return "booking-1"; }
            public String getPaymentId() { return "payment-1"; }
            public LocalDateTime getConfirmedAt() { return LocalDateTime.now().minusDays(1); }
            public Double getRevenue() { return 1000D; }
            public Double getRefunds() { return 0D; }
            public Double getPendingRefunds() { return 0D; }
        })) : customPayments;
        PayoutQuoteRepository quoteRepo = proxy(PayoutQuoteRepository.class, (method, args) -> switch (method) {
            case "save" -> { PayoutQuote q = (PayoutQuote) args[0]; quotes.put(q.getId(), q); yield q; }
            case "findById", "findForUpdate" -> Optional.ofNullable(quotes.get(args[0]));
            default -> throw new UnsupportedOperationException(method);
        });
        return new Fixture(new PayoutServiceImpl(payoutRepo, recRepo, accountRepo, events, users, bookings, payments, quoteRepo), savedPayouts, rec, kycEligible);
    }

    private interface MethodCall { Object call(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodCall call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> call.call(method.getName(), args));
    }
    private record Fixture(PayoutServiceImpl service, List<Payout> payouts, PayoutReconciliation reconciliation, AtomicBoolean kycEligible) {}
}
