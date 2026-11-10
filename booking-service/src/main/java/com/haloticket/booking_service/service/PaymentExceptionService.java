package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records payments that need a person and tells the admins through the outbox. Runs inside the caller's transaction so
 * the record, the notification and the booking change commit together. Idempotent: a repeated call for the same
 * (payment, kind), or (booking, kind) when there is no payment id, creates nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentExceptionService {

    public static final String NOTIFICATION_TOPIC = "haloticket.direct.notification.v1";
    public static final String NOTIFICATION_TYPE = "PAYMENT_EXCEPTION";
    static final int DETAIL_MAX = 500;
    static final int PROVIDER_MAX = 32;

    private final PaymentExceptionRepository repository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /** @return true when a new OPEN record was created, false when one already existed. */
    public boolean record(String bookingId, String bookingCode, String paymentId, PaymentExceptionKind kind,
                          Double amount, String provider, String detail) {
        boolean hasPayment = paymentId != null && !paymentId.isBlank();
        boolean exists = hasPayment
                ? repository.existsByPaymentIdAndKind(paymentId, kind)
                : repository.existsByBookingIdAndKind(bookingId, kind);
        if (exists) {
            log.info("[PAYMENT_EXCEPTION] already recorded bookingId={} kind={}", shortId(bookingId), kind);
            refreshOpenIssuanceDetail(bookingId, kind, detail);
            return false;
        }
        repository.save(PaymentException.builder()
                .bookingId(bookingId)
                .paymentId(hasPayment ? paymentId : null)
                .kind(kind)
                .amount(amount)
                .provider(truncate(blankToNull(provider), PROVIDER_MAX))
                .status(PaymentExceptionStatus.OPEN)
                .detail(truncate(detail, DETAIL_MAX))
                .createdAt(LocalDateTime.now())
                .build());
        notifyAdmins(bookingId, bookingCode, kind, amount);
        log.warn("[PAYMENT_EXCEPTION] recorded bookingId={} kind={} paymentId={}", shortId(bookingId), kind,
                hasPayment ? shortId(paymentId) : "-");
        return true;
    }

    /** An order whose tickets failed again (admin re-issue, redelivery): the record shows the LATEST reason, not the first one. */
    private void refreshOpenIssuanceDetail(String bookingId, PaymentExceptionKind kind, String detail) {
        if (kind != PaymentExceptionKind.ISSUANCE_FAILED || detail == null || detail.isBlank()) return;
        repository.findFirstByBookingIdAndKindAndStatus(bookingId, kind, PaymentExceptionStatus.OPEN).ifPresent(open -> {
            String latest = truncate(detail, DETAIL_MAX);
            if (!latest.equals(open.getDetail())) {
                open.setDetail(latest);
                repository.save(open);
            }
        });
    }

    private void notifyAdmins(String bookingId, String bookingCode, PaymentExceptionKind kind, Double amount) {
        // no personal data: booking code and amount only
        String code = bookingCode != null ? bookingCode : shortId(bookingId);
        String amountText = amount == null ? "?" : String.valueOf(Math.round(amount));
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("userId", "ADMIN");
        payload.put("recipientUserId", "ADMIN");
        payload.put("type", NOTIFICATION_TYPE);
        payload.put("title", "Thanh toán cần xử lý thủ công");
        payload.put("message", "Đơn " + code + " (" + kind + "), số tiền " + amountText + " VND cần đối soát.");
        payload.put("timestamp", LocalDateTime.now().toString());
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("BOOKING")
                    .aggregateId(bookingId)
                    .eventType("PAYMENT_EXCEPTION_NOTIFICATION")
                    .topic(NOTIFICATION_TOPIC)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise the admin notification", e);
        }
    }

    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
