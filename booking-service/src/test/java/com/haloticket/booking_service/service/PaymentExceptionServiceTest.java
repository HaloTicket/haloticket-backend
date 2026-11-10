package com.haloticket.booking_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentExceptionServiceTest {

    private PaymentExceptionRepository repository;
    private OutboxEventRepository outbox;
    private PaymentExceptionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentExceptionRepository.class);
        outbox = mock(OutboxEventRepository.class);
        service = new PaymentExceptionService(repository, outbox, new ObjectMapper());
    }

    @Test
    void newRecordIsOpenAndNotifiesAdminsThroughTheOutboxWithoutPersonalData() throws Exception {
        boolean created = service.record("b-1", "HALO-1", "pay-1", PaymentExceptionKind.LATE_PAYMENT_NO_STOCK, 200000d,
                "VNPAY", "x".repeat(900));

        assertThat(created).isTrue();
        ArgumentCaptor<PaymentException> saved = ArgumentCaptor.forClass(PaymentException.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentExceptionStatus.OPEN);
        assertThat(saved.getValue().getDetail()).hasSize(500);
        assertThat(saved.getValue().getPaymentId()).isEqualTo("pay-1");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getTopic()).isEqualTo("haloticket.direct.notification.v1");
        assertThat(event.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
        JsonNode payload = new ObjectMapper().readTree(event.getValue().getPayload());
        assertThat(payload.get("recipientUserId").asText()).isEqualTo("ADMIN");
        assertThat(payload.get("type").asText()).isEqualTo("PAYMENT_EXCEPTION");
        assertThat(payload.get("title").asText()).isNotBlank();
        assertThat(payload.get("message").asText()).contains("HALO-1").contains("200000");
    }

    @Test
    void aSecondIssuanceFailureOfTheSameOrderUpdatesTheReasonOfTheOpenRecord() {
        PaymentException open = PaymentException.builder().bookingId("b-1").kind(PaymentExceptionKind.ISSUANCE_FAILED)
                .status(PaymentExceptionStatus.OPEN).detail("first reason").build();
        when(repository.existsByBookingIdAndKind("b-1", PaymentExceptionKind.ISSUANCE_FAILED)).thenReturn(true);
        when(repository.findFirstByBookingIdAndKindAndStatus("b-1", PaymentExceptionKind.ISSUANCE_FAILED, PaymentExceptionStatus.OPEN))
                .thenReturn(java.util.Optional.of(open));

        boolean created = service.record("b-1", "HALO-1", null, PaymentExceptionKind.ISSUANCE_FAILED, 1d, "VIETQR", "latest reason");

        assertThat(created).isFalse();
        assertThat(open.getDetail()).isEqualTo("latest reason");
        verify(repository).save(open);
        verify(outbox, never()).save(any()); // no second notification for the same order
    }

    @Test
    void aRepeatedLatePaymentRecordKeepsItsOriginalDetail() {
        when(repository.existsByPaymentIdAndKind("pay-1", PaymentExceptionKind.LATE_PAYMENT_NO_STOCK)).thenReturn(true);

        service.record("b-1", "HALO-1", "pay-1", PaymentExceptionKind.LATE_PAYMENT_NO_STOCK, 1d, "VNPAY", "new text");

        verify(repository, never()).findFirstByBookingIdAndKindAndStatus(any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void repeatedRecordForTheSamePaymentAndKindCreatesNothing() {
        when(repository.existsByPaymentIdAndKind("pay-1", PaymentExceptionKind.LATE_PAYMENT_NO_STOCK)).thenReturn(true);

        boolean created = service.record("b-1", "HALO-1", "pay-1", PaymentExceptionKind.LATE_PAYMENT_NO_STOCK, 1d, "VNPAY", "d");

        assertThat(created).isFalse();
        verify(repository, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void withoutAPaymentIdTheBookingAndKindDecide() {
        service.record("b-1", "HALO-1", null, PaymentExceptionKind.ISSUANCE_FAILED, 1d, null, "d");
        verify(repository).existsByBookingIdAndKind("b-1", PaymentExceptionKind.ISSUANCE_FAILED);
        verify(repository, times(1)).save(any());

        when(repository.existsByBookingIdAndKind("b-1", PaymentExceptionKind.ISSUANCE_FAILED)).thenReturn(true);
        assertThat(service.record("b-1", "HALO-1", " ", PaymentExceptionKind.ISSUANCE_FAILED, 1d, null, "d")).isFalse();
        verify(repository, times(1)).save(any());
        verify(outbox, times(1)).save(any());
    }
}
