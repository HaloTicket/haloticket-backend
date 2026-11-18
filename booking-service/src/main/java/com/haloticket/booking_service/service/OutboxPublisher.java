package com.haloticket.booking_service.service;

import com.haloticket.booking_service.entity.OutboxEvent;
import com.haloticket.booking_service.enums.OutboxStatus;
import com.haloticket.booking_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Gửi các bản ghi outbox chờ xử lý lên Kafka (mẫu transactional outbox), thử lại tối đa 5 lần rồi đánh dấu {@code FAILED}. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Chạy mỗi giây: lấy tối đa 50 bản ghi {@code PENDING} cũ nhất, gửi lên Kafka và chờ broker xác nhận.
     * Thành công thì đánh dấu {@code PROCESSED}; lỗi thì tăng số lần thử và đánh dấu {@code FAILED} khi đạt 5 lần.
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
                // chờ broker xác nhận: nếu đánh dấu PROCESSED trước thì sẽ mất sự kiện khi gửi lỗi
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setStatus(OutboxStatus.PROCESSED);
                event.setProcessedAt(LocalDateTime.now());
                log.info("Published outbox event {} type {} to topic {}", event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
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
