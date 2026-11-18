package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
/** Repository thông điệp đã xử lý. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,String> { /** Kiểm tra consumer đã xử lý thông điệp có mã này chưa. */ boolean existsByConsumerNameAndMessageId(String consumerName, String messageId); }
