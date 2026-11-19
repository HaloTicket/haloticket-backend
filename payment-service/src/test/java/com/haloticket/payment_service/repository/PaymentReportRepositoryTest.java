package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// truy vấn báo cáo dùng DISTINCT ON của PostgreSQL nên H2 không chạy được: chỉ chạy khi có Postgres thật (RUN_DB_TESTS=1)
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "1")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@Transactional
class PaymentReportRepositoryTest {
    @Autowired PaymentRepository payments;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsRefundedSalesAndDeduplicatesBeforePeriodFilter() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(5);
        LocalDateTime to = LocalDateTime.now().plusMinutes(5);
        String retryBookingId = "report-retry-" + UUID.randomUUID();
        Payment earlier = savePayment(retryBookingId, PaymentStatus.SUCCESS);
        Payment retry = savePayment(retryBookingId, PaymentStatus.SUCCESS);
        addCompletion(earlier.getId(), from.minusMinutes(1));
        addCompletion(retry.getId(), from.plusMinutes(1));

        String refundedBookingId = "report-refunded-" + UUID.randomUUID();
        Payment refunded = savePayment(refundedBookingId, PaymentStatus.REFUNDED);
        addCompletion(refunded.getId(), from.plusMinutes(2));

        String failedBookingId = "report-failed-" + UUID.randomUUID();
        Payment failed = savePayment(failedBookingId, PaymentStatus.FAILED);
        addCompletion(failed.getId(), from.plusMinutes(3));

        long missingBefore = outbox.countBookingsMissingConfirmationTimestamp();
        String missingBookingId = "report-missing-" + UUID.randomUUID();
        savePayment(missingBookingId, PaymentStatus.SUCCESS);

        var facts = outbox.findSuccessfulPaymentFactsInRange(from, to, 100001);

        assertThat(facts).extracting(OutboxEventRepository.SuccessfulPaymentFact::getBookingId)
                .contains(refundedBookingId)
                .doesNotContain(retryBookingId, failedBookingId);
        assertThat(outbox.countBookingsMissingConfirmationTimestamp()).isEqualTo(missingBefore + 1);
    }

    private Payment savePayment(String bookingId, PaymentStatus status) {
        return payments.saveAndFlush(Payment.builder().bookingId(bookingId).amount(100.0).status(status).build());
    }

    private void addCompletion(String paymentId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, status, retry_count, created_at) " +
                        "VALUES (?, 'PAYMENT', ?, 'PAYMENT_COMPLETED', 'haloticket.payment.completed.v1', '{}', 'PENDING', 0, ?)",
                UUID.randomUUID().toString(), paymentId, Timestamp.valueOf(createdAt));
    }
}
