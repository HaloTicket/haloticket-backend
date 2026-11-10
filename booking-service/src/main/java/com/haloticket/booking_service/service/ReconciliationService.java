package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.config.ReconciliationProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Admin reconciliation: payments that need a person, paid orders that have too few tickets, and stock that does not add
 * up. The last two ask ticket-service; when it cannot answer the result is a 502, never an empty list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    public static final String SOURCE_UNAVAILABLE = "RECONCILIATION_SOURCE_UNAVAILABLE";
    static final int ISSUED_COUNTS_MAX = 200;

    /** The last six fields (customer, payment method, event) are filled by ReconciliationDetails and are null when a lookup failed. */
    public record ExceptionItem(String id, String bookingId, String bookingCode, String paymentId, String kind, Double amount,
                                String provider, String status, String detail, LocalDateTime createdAt,
                                LocalDateTime resolvedAt, String resolvedBy, String resolutionNote,
                                String customerName, String customerEmail, String customerPhone, String paymentMethod,
                                String eventId, String eventTitle) {
        public ExceptionItem(String id, String bookingId, String bookingCode, String paymentId, String kind, Double amount,
                             String provider, String status, String detail, LocalDateTime createdAt,
                             LocalDateTime resolvedAt, String resolvedBy, String resolutionNote) {
            this(id, bookingId, bookingCode, paymentId, kind, amount, provider, status, detail, createdAt, resolvedAt, resolvedBy,
                    resolutionNote, null, null, null, null, null, null);
        }

        public ExceptionItem withDetails(String name, String email, String phone, String method, String eventId, String eventTitle) {
            return new ExceptionItem(id, bookingId, bookingCode, paymentId, kind, amount, provider, status, detail, createdAt,
                    resolvedAt, resolvedBy, resolutionNote, name, email, phone, method, eventId, eventTitle);
        }
    }

    public record ExceptionPage(List<ExceptionItem> items, long total, int page, int size) {}

    public record PaidWithoutTicketsItem(String bookingId, String bookingCode, LocalDateTime paidAt, long expectedTickets,
                                         long issuedTickets, long minutesSincePaid,
                                         String customerName, String customerEmail, String customerPhone, String paymentMethod,
                                         Double amount, String eventId, String eventTitle) {
        public PaidWithoutTicketsItem(String bookingId, String bookingCode, LocalDateTime paidAt, long expectedTickets,
                                      long issuedTickets, long minutesSincePaid) {
            this(bookingId, bookingCode, paidAt, expectedTickets, issuedTickets, minutesSincePaid, null, null, null, null, null, null, null);
        }

        public PaidWithoutTicketsItem withDetails(String name, String email, String phone, String method, Double amount,
                                                  String eventId, String eventTitle) {
            return new PaidWithoutTicketsItem(bookingId, bookingCode, paidAt, expectedTickets, issuedTickets, minutesSincePaid,
                    name, email, phone, method, amount, eventId, eventTitle);
        }
    }

    public record PaidWithoutTickets(List<PaidWithoutTicketsItem> items, int total, boolean truncated) {}

    public record StockDiscrepancies(List<Map<String, Object>> items, int total) {}

    private final PaymentExceptionRepository exceptionRepository;
    private final BookingRepository bookingRepository;
    private final TicketStockClient ticketStockClient;
    private final GaReservationProperties ticketProperties;
    private final ReconciliationProperties properties;

    /** Customer, payment and event names for the rows. Optional: without it the rows carry codes and amounts only. */
    @Autowired(required = false)
    private ReconciliationDetails details;

    /** A count is null when its source could not be read. */
    public Map<String, Object> summary() {
        autoResolveIssued();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openExceptions", exceptionRepository.countByStatus(PaymentExceptionStatus.OPEN));
        out.put("paidWithoutTickets", countOrNull(() -> paidWithoutTickets().total()));
        out.put("stockDiscrepancies", countOrNull(() -> stockDiscrepancies().total()));
        return out;
    }

    private Integer countOrNull(Supplier<Integer> source) {
        try {
            return source.get();
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    /**
     * An open ISSUANCE_FAILED exception whose order now has all its tickets (re-issued by the admin, the customer, the scheduler
     * or after a fix) is closed here, by "system", so nobody has to mark it by hand. Best effort: when ticket-service cannot
     * answer nothing is closed.
     */
    public int autoResolveIssued() {
        try {
            List<PaymentException> open = exceptionRepository.findTop200ByKindAndStatus(
                    com.haloticket.booking_service.enums.PaymentExceptionKind.ISSUANCE_FAILED, PaymentExceptionStatus.OPEN);
            if (open.isEmpty()) return 0;
            List<String> ids = open.stream().map(PaymentException::getBookingId).distinct().toList();
            Map<String, Long> expected = bookingRepository.findReportFactsByBookingIds(ids).stream()
                    .collect(Collectors.toMap(BookingRepository.ReportFact::getBookingId,
                            f -> f.getTickets() == null ? 0L : f.getTickets(), (a, b) -> a));
            Map<String, Integer> issued = new HashMap<>();
            for (int i = 0; i < ids.size(); i += ISSUED_COUNTS_MAX) {
                issued.putAll(issuedCounts(ids.subList(i, Math.min(ids.size(), i + ISSUED_COUNTS_MAX))));
            }
            List<PaymentException> done = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (PaymentException e : open) {
                long want = expected.getOrDefault(e.getBookingId(), 0L);
                if (want > 0 && issued.getOrDefault(e.getBookingId(), 0) >= want) {
                    e.setStatus(PaymentExceptionStatus.RESOLVED);
                    e.setResolvedAt(now);
                    e.setResolvedBy("system");
                    e.setResolutionNote("Vé đã được phát hành đầy đủ, hệ thống tự ghi nhận.");
                    done.add(e);
                }
            }
            if (!done.isEmpty()) {
                exceptionRepository.saveAll(done);
                log.info("[PAYMENT_EXCEPTION] auto-resolved {} issuance exception(s) whose tickets now exist", done.size());
            }
            return done.size();
        } catch (RuntimeException e) {
            log.warn("[PAYMENT_EXCEPTION] auto-resolve skipped: {}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Repairs the remaining stock of one ticket type in ticket-service; its refusals (404/409/422) are passed on unchanged. */
    public Map<String, Object> fixStock(String ticketTypeId) {
        Map<String, Object> row;
        try {
            row = ticketStockClient.fixStock(token(), ticketTypeId);
        } catch (feign.FeignException e) {
            int code = e.status();
            if (code == 404 || code == 409 || code == 422) {
                throw new ResponseStatusException(HttpStatus.valueOf(code), "STOCK_FIX_REFUSED_" + code, e);
            }
            throw unavailable("stock fix failed with status " + code, e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e.getClass().getSimpleName(), e);
        }
        if (row == null) throw unavailable("stock fix returned no body", null);
        log.info("[RECONCILIATION] stock fixed ticketTypeId={}", ticketTypeId);
        List<Map<String, Object>> single = List.of(row);
        return (details == null ? single : details.stockRows(single)).get(0);
    }

    public ExceptionPage exceptions(PaymentExceptionStatus status, int page, int size) {
        autoResolveIssued();
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<PaymentException> result = status == null ? exceptionRepository.findAll(request)
                : exceptionRepository.findByStatus(status, request);
        Map<String, String> codes = bookingRepository.findAllById(
                        result.getContent().stream().map(PaymentException::getBookingId).distinct().toList())
                .stream().collect(Collectors.toMap(Booking::getId, Booking::getBookingCode, (a, b) -> a));
        List<ExceptionItem> items = result.getContent().stream().map(e -> toItem(e, codes.get(e.getBookingId()))).toList();
        return new ExceptionPage(details == null ? items : details.exceptionItems(items), result.getTotalElements(), page, size);
    }

    /** Marks an exception handled. The booking is not touched. */
    @Transactional
    public ExceptionItem resolve(String id, String note, boolean refundedManually, String resolvedBy) {
        String cleaned = note == null ? "" : note.trim();
        if (cleaned.length() < 3 || cleaned.length() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESOLUTION_NOTE");
        }
        PaymentException exception = exceptionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PAYMENT_EXCEPTION_NOT_FOUND"));
        if (exception.getStatus() != PaymentExceptionStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PAYMENT_EXCEPTION_ALREADY_RESOLVED");
        }
        exception.setStatus(refundedManually ? PaymentExceptionStatus.REFUNDED_MANUALLY : PaymentExceptionStatus.RESOLVED);
        exception.setResolvedAt(LocalDateTime.now());
        exception.setResolvedBy(PaymentExceptionService.truncate(resolvedBy, 64));
        exception.setResolutionNote(cleaned);
        exceptionRepository.save(exception);
        log.info("[PAYMENT_EXCEPTION] resolved id={} status={} by={}", id, exception.getStatus(), resolvedBy);
        String code = bookingRepository.findById(exception.getBookingId()).map(Booking::getBookingCode).orElse(null);
        return toItem(exception, code);
    }

    private static ExceptionItem toItem(PaymentException e, String bookingCode) {
        return new ExceptionItem(e.getId(), e.getBookingId(), bookingCode, e.getPaymentId(), e.getKind().name(), e.getAmount(),
                e.getProvider(), e.getStatus().name(), e.getDetail(), e.getCreatedAt(), e.getResolvedAt(), e.getResolvedBy(),
                e.getResolutionNote());
    }

    /**
     * PAID and CONFIRMED orders both should have tickets (PAID = payment applied and BOOKING_CONFIRMED sent; CONFIRMED is
     * reserved and not set by any code today). Checks orders paid between lookbackDays ago and paidGraceMinutes ago, oldest
     * first, batchSize at a time and at most maxBatches batches, and keeps those with fewer tickets than expected.
     */
    public PaidWithoutTickets paidWithoutTickets() {
        int batch = Math.max(1, Math.min(properties.getBatchSize(), ISSUED_COUNTS_MAX));
        int maxBatches = Math.max(1, properties.getMaxBatches());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime to = now.minusMinutes(Math.max(0, properties.getPaidGraceMinutes()));
        LocalDateTime from = now.minusDays(Math.max(1, properties.getLookbackDays()));
        List<PaidWithoutTicketsItem> found = new ArrayList<>();
        boolean truncated = false;
        for (int pageIndex = 0; pageIndex < maxBatches; pageIndex++) {
            List<BookingRepository.PaidCandidate> candidates =
                    bookingRepository.findPaidCandidates(from, to, PageRequest.of(pageIndex, batch));
            if (candidates.isEmpty()) break;
            Map<String, Integer> issued = issuedCounts(
                    candidates.stream().map(BookingRepository.PaidCandidate::getBookingId).toList());
            for (BookingRepository.PaidCandidate c : candidates) {
                long expected = c.getExpectedTickets() == null ? 0 : c.getExpectedTickets();
                long have = issued.getOrDefault(c.getBookingId(), 0);
                if (expected > 0 && have < expected) {
                    found.add(new PaidWithoutTicketsItem(c.getBookingId(), c.getBookingCode(), c.getPaidAt(), expected, have,
                            c.getPaidAt() == null ? 0 : Duration.between(c.getPaidAt(), now).toMinutes()));
                }
            }
            if (candidates.size() < batch) break;
            if (pageIndex == maxBatches - 1) truncated = true;
        }
        return new PaidWithoutTickets(details == null ? found : details.paidItems(found), found.size(), truncated);
    }

    public StockDiscrepancies stockDiscrepancies() {
        List<Map<String, Object>> rows = fromTicketService(
                () -> ticketStockClient.stockReconciliation(token(), Math.max(1, properties.getStockLimit())));
        if (rows == null) throw unavailable("stock-reconciliation returned no body", null);
        return new StockDiscrepancies(details == null ? rows : details.stockRows(rows), rows.size());
    }

    private Map<String, Integer> issuedCounts(List<String> bookingIds) {
        Map<String, Map<String, Integer>> body = fromTicketService(
                () -> ticketStockClient.issuedCounts(token(), Map.of("bookingIds", bookingIds)));
        // a body without counts must not read as "no tickets for anyone"
        if (body == null || body.get("counts") == null) throw unavailable("issued-counts returned no counts", null);
        return new HashMap<>(body.get("counts"));
    }

    private String token() {
        String token = ticketProperties.getInternalToken();
        if (token == null || token.isBlank()) throw unavailable("internal token is not configured", null);
        return token;
    }

    private <T> T fromTicketService(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e.getClass().getSimpleName(), e);
        }
    }

    private ResponseStatusException unavailable(String why, Throwable cause) {
        log.error("[RECONCILIATION] ticket-service source unavailable: {}", why);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, SOURCE_UNAVAILABLE, cause);
    }
}
