package com.haloticket.marketplace_service.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
/**
 * Dấu vết thông điệp Kafka đã xử lý theo cặp (tên consumer, mã thông điệp), dùng để bỏ qua thông điệp trùng
 * và bảo đảm xử lý idempotent.
 */
@Entity @Table(name="processed_events", uniqueConstraints=@UniqueConstraint(name="uk_processed_consumer_message", columnNames={"consumer_name","message_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcessedEvent { @Id @GeneratedValue(strategy=GenerationType.UUID) private String id; @Column(name="consumer_name",nullable=false) private String consumerName; @Column(name="message_id",nullable=false) private String messageId; @Column(nullable=false) private Instant processedAt; }
