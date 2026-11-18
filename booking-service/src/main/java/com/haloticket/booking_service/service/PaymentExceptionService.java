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
 * Ghi nhận các khoản thanh toán cần người xử lý và báo cho admin qua outbox. Chạy trong giao dịch của bên gọi để
 * bản ghi, thông báo và thay đổi của đơn cùng commit. Idempotent: gọi lặp cho cùng (thanh toán, loại), hoặc
 * (đơn, loại) khi không có mã thanh toán, thì không tạo thêm.
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

    /**
     * Ghi nhận một ngoại lệ thanh toán ở trạng thái OPEN và báo cho admin; nếu đã có thì chỉ làm mới lý do (với {@code ISSUANCE_FAILED}).
     * @param bookingId mã đơn
     * @param bookingCode mã hiển thị của đơn, dùng trong thông báo
     * @param paymentId mã thanh toán, có thể rỗng
     * @param kind loại ngoại lệ
     * @param amount số tiền
     * @param provider cổng thanh toán, bị cắt còn tối đa {@code PROVIDER_MAX} ký tự
     * @param detail lý do chi tiết, bị cắt còn tối đa {@code DETAIL_MAX} ký tự
     * @return true khi tạo mới bản ghi OPEN, false khi đã có sẵn
     */
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

    /** Đơn có vé lại phát hành lỗi (admin phát lại, thông điệp giao lại): bản ghi hiển thị lý do MỚI NHẤT chứ không phải lần đầu. */
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

    /**
     * Ghi sự kiện outbox thông báo cho admin có khoản thanh toán cần đối soát thủ công.
     * @param bookingId mã đơn
     * @param bookingCode mã hiển thị của đơn, thiếu thì dùng id rút gọn
     * @param kind loại ngoại lệ
     * @param amount số tiền, null thì hiển thị "?"
     * @throws IllegalStateException khi không tạo được JSON thông báo
     */
    private void notifyAdmins(String bookingId, String bookingCode, PaymentExceptionKind kind, Double amount) {
        // không có dữ liệu cá nhân: chỉ mã đơn và số tiền
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

    /** Cắt chuỗi còn tối đa {@code max} ký tự; null giữ nguyên. */
    static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Chuỗi rỗng hoặc chỉ có khoảng trắng thì thành null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Rút gọn id còn 8 ký tự đầu để ghi log. */
    private static String shortId(String id) {
        return id == null ? "" : id.substring(0, Math.min(8, id.length()));
    }
}
