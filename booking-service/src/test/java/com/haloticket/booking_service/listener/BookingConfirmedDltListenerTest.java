package com.haloticket.booking_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import com.haloticket.booking_service.repository.PaymentExceptionRepository;
import com.haloticket.booking_service.service.PaymentExceptionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingConfirmedDltListenerTest {

    private BookingRepository bookings;
    private PaymentServiceClient payments;
    private PaymentExceptionRepository repository;
    private OutboxEventRepository outbox;
    private BookingConfirmedDltListener listener;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        payments = mock(PaymentServiceClient.class);
        repository = mock(PaymentExceptionRepository.class);
        outbox = mock(OutboxEventRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        listener = new BookingConfirmedDltListener(bookings, payments,
                new PaymentExceptionService(repository, outbox, mapper), mapper);
        when(bookings.findById("b-1")).thenReturn(Optional.of(Booking.builder().id("b-1").bookingCode("HALO-1")
                .totalAmount(300000d).build()));
    }

    private static ConsumerRecord<String, String> dead(String key, String value, String exceptionMessage) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("haloticket.booking.confirmed.v1.DLT", 0, 7L, key, value);
        if (exceptionMessage != null) {
            record.headers().add("kafka_dlt-exception-message", exceptionMessage.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void deadLetterCreatesOneIssuanceFailedRecordWithTheTruncatedCause() {
        when(payments.getPaymentMethodsByBookingIds(List.of("b-1"))).thenReturn(Map.of("b-1", "MOMO"));

        listener.handleDeadLetter(dead("b-1", "{\"bookingId\":\"b-1\"}", "y".repeat(1000)));

        ArgumentCaptor<PaymentException> saved = ArgumentCaptor.forClass(PaymentException.class);
        verify(repository).save(saved.capture());
        PaymentException record = saved.getValue();
        assertThat(record.getKind()).isEqualTo(PaymentExceptionKind.ISSUANCE_FAILED);
        assertThat(record.getPaymentId()).isNull();
        assertThat(record.getAmount()).isEqualTo(300000d);
        assertThat(record.getProvider()).isEqualTo("MOMO");
        assertThat(record.getDetail()).contains("y".repeat(300)).doesNotContain("y".repeat(301));
        verify(outbox, times(1)).save(any());
    }

    @Test
    void redeliveredDeadLetterDoesNotCreateASecondRecord() {
        AtomicInteger saves = new AtomicInteger();
        when(repository.save(any())).thenAnswer(invocation -> {
            saves.incrementAndGet();
            return invocation.getArgument(0);
        });
        when(repository.existsByBookingIdAndKind("b-1", PaymentExceptionKind.ISSUANCE_FAILED)).thenAnswer(invocation -> saves.get() > 0);

        listener.handleDeadLetter(dead("b-1", "{\"bookingId\":\"b-1\"}", "boom"));
        listener.handleDeadLetter(dead("b-1", "{\"bookingId\":\"b-1\"}", "boom"));

        assertThat(saves.get()).isEqualTo(1);
        verify(outbox, times(1)).save(any());
    }

    @Test
    void paymentServiceOutageOnlyLeavesTheProviderEmpty() {
        when(payments.getPaymentMethodsByBookingIds(any())).thenThrow(new RuntimeException("down"));

        listener.handleDeadLetter(dead("b-1", "{\"bookingId\":\"b-1\"}", null));

        ArgumentCaptor<PaymentException> saved = ArgumentCaptor.forClass(PaymentException.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isNull();
    }

    @Test
    void messageWithoutABookingIdIsIgnored() {
        listener.handleDeadLetter(dead(null, "not json", "boom"));
        verify(repository, never()).save(any());
    }
}
