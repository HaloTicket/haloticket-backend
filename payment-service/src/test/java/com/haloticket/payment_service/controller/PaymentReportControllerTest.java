package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import com.haloticket.payment_service.config.SecurityConfig;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentReportControllerTest {
    @Test
    void requestsScopedRowsAndReportsGlobalMissingConfirmationCount() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        var first = fact("booking-1", "payment-1", LocalDateTime.parse("2026-05-02T10:00:00"), 70.5);
        when(repository.findSuccessfulPaymentFactsInRange(LocalDateTime.parse("2026-05-01T07:00:00"),
                LocalDateTime.parse("2026-06-01T07:00:00"), 100001)).thenReturn(List.of(first));
        when(repository.countBookingsMissingConfirmationTimestamp()).thenReturn(1L);
        PaymentReportController controller = new PaymentReportController(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "sourceZone", "Asia/Ho_Chi_Minh");

        var result = controller.reportPayments(Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(result.payments()).hasSize(1).extracting(PaymentReportController.PaymentReportItem::bookingId).containsExactly("booking-1");
        assertThat(result.payments().getFirst().confirmedAt()).isEqualTo(Instant.parse("2026-05-02T03:00:00Z"));
        assertThat(result.missingTimestampCount()).isEqualTo(1);
        verify(repository).findSuccessfulPaymentFactsInRange(LocalDateTime.parse("2026-05-01T07:00:00"),
                LocalDateTime.parse("2026-06-01T07:00:00"), 100001);
        verify(repository).countBookingsMissingConfirmationTimestamp();
    }

    @Test
    void emitsEarliestPaymentInRangeWithExactDecimalConversion() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.findSuccessfulPaymentFactsInRange(any(), any(), eq(100001))).thenReturn(List.of(
                fact("booking", "pay", LocalDateTime.parse("2026-05-05T10:00:00"), 12.34)));
        when(repository.countBookingsMissingConfirmationTimestamp()).thenReturn(0L);
        PaymentReportController controller = new PaymentReportController(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "sourceZone", "UTC");

        var result = controller.reportPayments(Instant.parse("2026-05-05T10:00:00Z"), Instant.parse("2026-05-06T00:00:00Z"));

        assertThat(result.payments()).hasSize(1);
        assertThat(result.payments().getFirst().revenue()).isEqualByComparingTo("12.34");
    }

    @Test
    void rejectsOversizedRangeAndResult() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        PaymentReportController controller = new PaymentReportController(repository);
        assertThatThrownBy(() -> controller.reportPayments(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-02-03T00:00:00Z")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(repository.findSuccessfulPaymentFactsInRange(any(), any(), eq(100001)))
                .thenReturn(java.util.Collections.nCopies(100001, fact("b", "p", LocalDateTime.parse("2026-01-02T00:00:00"), 1.0)));
        assertThatThrownBy(() -> controller.reportPayments(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode())
                        .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void mapsRealmAdminRoleToSpringRoleAuthority() {
        var converter = new SecurityConfig().jwtAuthenticationConverter();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "admin")
                .claim("realm_access", Map.of("roles", List.of("ADMIN"))).build();
        assertThat(converter.convert(jwt).getAuthorities()).extracting("authority").contains("ROLE_ADMIN");
    }

    private static OutboxEventRepository.SuccessfulPaymentFact fact(String booking, String payment, LocalDateTime at, Double revenue) {
        return new OutboxEventRepository.SuccessfulPaymentFact() {
            public String getBookingId() { return booking; }
            public String getPaymentId() { return payment; }
            public LocalDateTime getConfirmedAt() { return at; }
            public Double getRevenue() { return revenue; }
        };
    }
}
