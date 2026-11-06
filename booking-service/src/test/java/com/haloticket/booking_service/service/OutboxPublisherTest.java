package com.haloticket.booking_service.service;

import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafka;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafka = mock(KafkaTemplate.class);
        publisher = new OutboxPublisher(repository, kafka);
    }

    private OutboxEvent pending(int retries) {
        return OutboxEvent.builder().id("o-1").aggregateId("b-1").eventType("BOOKING_CONFIRMED")
                .topic("haloticket.booking.confirmed.v1").payload("{}").status(OutboxStatus.PENDING).retryCount(retries).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgedSendMarksProcessed() {
        OutboxEvent event = pending(0);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void sendThatFailsAfterwardsLeavesTheEventPendingAndCountsTheRetry() {
        OutboxEvent event = pending(0);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker not available")));

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getProcessedAt()).isNull();
    }

    @Test
    void fifthFailureMarksTheEventFailed() {
        OutboxEvent event = pending(4);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker not available")));

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(5);
    }

    @Test
    void sendThatThrowsImmediatelyAlsoStaysPending() {
        OutboxEvent event = pending(1);
        when(repository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("serializer"));

        publisher.publishPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(2);
    }
}
