package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.repository.TransferPolicyOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor @Slf4j
public class TransferPolicyOutboxPublisher {
    public static final String TOPIC = "haloticket.event.transfer-policy.changed.v1";
    private final TransferPolicyOutboxRepository repository;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper mapper;

    @Scheduled(fixedDelayString = "${event.transfer-policy.outbox-poll-ms:1000}")
    @Transactional
    public void publish() {
        for (var item : repository.lockDue(Instant.now())) {
            try {
                kafka.send(TOPIC, item.getEventId(), mapper.readTree(item.getPayload())).get(10, TimeUnit.SECONDS);
                item.setStatus("PUBLISHED");
                item.setPublishedAt(Instant.now());
            } catch (Exception failure) {
                item.setRetryCount(item.getRetryCount() + 1);
                item.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, item.getRetryCount()))));
                log.warn("Transfer policy outbox publish failed for {} (attempt {})", item.getId(), item.getRetryCount(), failure);
            }
        }
    }
}
