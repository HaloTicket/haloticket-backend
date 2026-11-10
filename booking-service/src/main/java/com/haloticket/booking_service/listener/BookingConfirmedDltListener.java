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
 * Reads the dead-letter topic of haloticket.booking.confirmed.v1: a paid order whose tickets could not be issued after
 * every retry. Creates an ISSUANCE_FAILED payment exception (once per booking) and tells the admins. Its own consumer
 * group, so it never takes messages from the main listeners.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedDltListener {

    public static final String DLT_TOPIC = "haloticket.booking.confirmed.v1.DLT";
    // KafkaHeaders.DLT_EXCEPTION_MESSAGE
    static final String EXCEPTION_MESSAGE_HEADER = "kafka_dlt-exception-message";
    static final int MESSAGE_MAX = 400;
    /** Spring wraps the real failure: "Listener method '...' threw exception; <what the listener said>". Only the second part is the reason. */
    private static final String LISTENER_PREFIX = "threw exception;";

    private final BookingRepository bookingRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final PaymentExceptionService paymentExceptionService;
    private final ObjectMapper objectMapper;

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

    private String exceptionMessage(ConsumerRecord<String, String> record) {
        Header header = record.headers() == null ? null : record.headers().lastHeader(EXCEPTION_MESSAGE_HEADER);
        if (header == null || header.value() == null) return "";
        String text = new String(header.value(), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        int at = text.indexOf(LISTENER_PREFIX);
        if (at >= 0 && at + LISTENER_PREFIX.length() < text.length()) text = text.substring(at + LISTENER_PREFIX.length()).trim();
        return text.length() <= MESSAGE_MAX ? text : text.substring(0, MESSAGE_MAX);
    }

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
