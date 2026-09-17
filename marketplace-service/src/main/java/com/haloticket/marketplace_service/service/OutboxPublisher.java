package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.entity.OutboxEvent;
import com.haloticket.marketplace_service.enums.OutboxStatus;
import com.haloticket.marketplace_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${marketplace.outbox.poll-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = repository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
            } catch (Exception exception) {
                int retries = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
                event.setRetryCount(retries);
                if (retries >= 10) event.setStatus(OutboxStatus.FAILED);
                log.warn("Could not publish marketplace outbox event {} (attempt {})", event.getId(), retries, exception);
            }
        }
        repository.saveAll(events);
    }
}
