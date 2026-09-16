package com.haloticket.payment_service.service;

import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.enums.OutboxStatus;
import com.haloticket.payment_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload());
                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(LocalDateTime.now());
                log.info("Published outbox event {} type {} to topic {}", event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}", event.getId(), e);
                int retryCount = event.getRetryCount() != null ? event.getRetryCount() + 1 : 1;
                event.setRetryCount(retryCount);
                if (retryCount >= 5) {
                    event.setStatus(OutboxStatus.FAILED);
                }
            }
        }
        outboxEventRepository.saveAll(pendingEvents);
    }
}
