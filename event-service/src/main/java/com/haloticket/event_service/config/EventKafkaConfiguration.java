package com.haloticket.event_service.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/** Cấu hình Kafka producer của event-service: khóa dạng chuỗi, giá trị tuần tự hóa JSON. */
@Configuration
public class EventKafkaConfiguration {
    /**
     * Tạo producer factory với khóa {@code StringSerializer} và giá trị {@code JsonSerializer}.
     * @param bootstrapServers địa chỉ Kafka broker, mặc định {@code localhost:9092}
     * @return producer factory dùng chung cho template
     */
    @Bean
    ProducerFactory<String, Object> eventKafkaProducerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    /**
     * Tạo {@code KafkaTemplate} để phát sự kiện lên Kafka.
     * @param factory producer factory đã cấu hình
     * @return template gửi thông điệp
     */
    @Bean
    KafkaTemplate<String, Object> eventKafkaTemplate(ProducerFactory<String, Object> factory) {
        return new KafkaTemplate<>(factory);
    }
}
