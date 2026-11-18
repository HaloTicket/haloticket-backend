package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.enums.OutboxStatus;
import com.haloticket.ticket_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Định kỳ gửi các sự kiện outbox đang chờ lên Kafka theo mô hình transactional outbox. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Mỗi giây lấy tối đa 50 sự kiện {@code PENDING} cũ nhất và gửi lên Kafka (chờ tối đa 10 giây mỗi sự kiện). Gửi được thì đánh dấu
     * {@code PROCESSED}; lỗi thì tăng số lần thử và sau 5 lần đánh dấu {@code FAILED}.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
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
