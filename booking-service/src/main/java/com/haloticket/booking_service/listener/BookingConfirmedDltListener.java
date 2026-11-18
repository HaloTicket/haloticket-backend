package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.service.PaymentExceptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Đọc topic dead-letter của {@code haloticket.booking.confirmed.v1}: đơn đã thanh toán nhưng không phát hành được vé sau mọi lần thử lại.
 * Tạo ngoại lệ thanh toán {@code ISSUANCE_FAILED} (một lần cho mỗi đơn) và báo cho admin. Dùng consumer group riêng để không lấy thông điệp của các listener chính.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedDltListener {

    public static final String DLT_TOPIC = "haloticket.booking.confirmed.v1.DLT";
    // KafkaHeaders.DLT_EXCEPTION_MESSAGE
    static final String EXCEPTION_MESSAGE_HEADER = "kafka_dlt-exception-message";
    static final int MESSAGE_MAX = 400;
    /** Spring bọc lỗi thật dưới dạng "Listener method '...' threw exception; <điều listener báo>". Chỉ phần sau mới là lý do. */
    private static final String LISTENER_PREFIX = "threw exception;";

    private final BookingRepository bookingRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final PaymentExceptionService paymentExceptionService;
    private final ObjectMapper objectMapper;

    /**
     * Xử lý một thông điệp dead-letter: ghi nhận ngoại lệ thanh toán {@code ISSUANCE_FAILED} kèm lý do lỗi, số tiền và cổng thanh toán của đơn.
     * Bỏ qua thông điệp nếu không xác định được mã đơn.
     * @param record bản ghi dead-letter từ Kafka
     */
    @KafkaListener(topics = DLT_TOPIC, groupId = "booking-service-dlt")
    @Transactional
    public void handleDeadLetter(ConsumerRecord<String, String> record) {
        String bookingId = bookingIdOf(record);
        if (bookingId == null) {
            log.warn("[ISSUANCE_FAILED] dead letter without a bookingId at offset {}, ignored", record.offset());
            return;
        }
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        String cause = exceptionMessage(record);
        String detail = "Ticket issuance failed after all retries" + (cause.isEmpty() ? "" : ": " + cause);

        Double amount = booking != null ? booking.getTotalAmount() : null;
        String provider = booking != null ? providerOf(bookingId) : null;
        paymentExceptionService.record(bookingId, booking != null ? booking.getBookingCode() : null, null,
                PaymentExceptionKind.ISSUANCE_FAILED, amount, provider, detail);
    }

    /**
     * Lấy mã đơn từ trường {@code bookingId} của payload JSON; nếu không có thì dùng key của bản ghi.
     * @param record bản ghi dead-letter
     * @return mã đơn, hoặc null nếu không xác định được
     */
    private String bookingIdOf(ConsumerRecord<String, String> record) {
        try {
            if (record.value() != null) {
                JsonNode node = objectMapper.readTree(record.value());
                String id = node.path("bookingId").asText("");
                if (!id.isBlank()) return id;
            }
        } catch (Exception e) {
            log.warn("[ISSUANCE_FAILED] dead letter payload is not JSON: {}", e.getClass().getSimpleName());
        }
        return record.key() != null && !record.key().isBlank() ? record.key() : null;
    }

    /**
     * Lấy lý do lỗi từ header của dead-letter: gộp khoảng trắng, bỏ phần Spring bọc phía trước và cắt tối đa {@code MESSAGE_MAX} ký tự.
     * @param record bản ghi dead-letter
     * @return lý do lỗi, chuỗi rỗng nếu không có header
     */
    private String exceptionMessage(ConsumerRecord<String, String> record) {
        Header header = record.headers() == null ? null : record.headers().lastHeader(EXCEPTION_MESSAGE_HEADER);
        if (header == null || header.value() == null) return "";
        String text = new String(header.value(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        int at = text.indexOf(LISTENER_PREFIX);
        if (at >= 0 && at + LISTENER_PREFIX.length() < text.length()) text = text.substring(at + LISTENER_PREFIX.length()).trim();
        return text.length() <= MESSAGE_MAX ? text : text.substring(0, MESSAGE_MAX);
    }

    /**
     * Hỏi payment-service cổng thanh toán đã dùng cho đơn; lỗi kết nối chỉ làm thiếu thông tin này.
     * @param bookingId mã đơn
     * @return tên cổng thanh toán, hoặc null nếu không biết
     */
    private String providerOf(String bookingId) {
        try {
            var methods = paymentServiceClient.getPaymentMethodsByBookingIds(List.of(bookingId));
            return methods == null ? null : methods.get(bookingId);
        } catch (RuntimeException e) {
            log.warn("[ISSUANCE_FAILED] payment provider unavailable for bookingId={}", bookingId.substring(0, Math.min(8, bookingId.length())));
            return null;
        }
    }
}
