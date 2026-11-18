package com.haloticket.notification_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Cấu hình Kafka của {@code notification-service}: tạo topic OTP và thông báo cùng topic DLT,
 * producer JSON, consumer chuỗi và error handler chuyển thông điệp lỗi sang DLT.
 */
@Configuration
@EnableKafka
public class AuthKafkaConfiguration {
    /**
     * Khai báo topic OTP xác thực (3 partition, 1 replica).
     *
     * @param topic tên topic, lấy từ {@code notification.kafka.auth-otp-topic}
     * @return định nghĩa topic
     */
    @Bean
    NewTopic authOtpTopic(@Value("${notification.kafka.auth-otp-topic:haloticket.auth.otp.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /**
     * Khai báo topic dead-letter của OTP (tên topic gốc cộng {@code .DLT}).
     *
     * @param topic tên topic OTP gốc
     * @return định nghĩa topic DLT
     */
    @Bean
    NewTopic authOtpDeadLetterTopic(@Value("${notification.kafka.auth-otp-topic:haloticket.auth.otp.v1}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * Khai báo topic thông báo (3 partition, 1 replica).
     *
     * @param topic tên topic, lấy từ {@code notification.kafka.topic}
     * @return định nghĩa topic
     */
    @Bean
    NewTopic notificationTopic(@Value("${notification.kafka.topic:haloticket.notifications.v1}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /**
     * Khai báo topic dead-letter của thông báo (tên topic gốc cộng {@code .DLT}).
     *
     * @param topic tên topic thông báo gốc
     * @return định nghĩa topic DLT
     */
    @Bean
    NewTopic notificationDeadLetterTopic(@Value("${notification.kafka.topic:haloticket.notifications.v1}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }

    /**
     * Tạo {@code KafkaTemplate} gửi giá trị dạng JSON, được dùng để đẩy thông điệp lỗi sang DLT.
     *
     * @param bootstrapServers địa chỉ broker Kafka
     * @return template gửi thông điệp
     */
    @Bean
    KafkaTemplate<String, Object> authOtpKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }

    /**
     * Tạo {@code KafkaAdmin} để tự tạo các topic đã khai báo khi khởi động.
     *
     * @param bootstrapServers địa chỉ broker Kafka
     * @return đối tượng quản trị Kafka
     */
    @Bean
    KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        return new KafkaAdmin(Map.of("bootstrap.servers", bootstrapServers));
    }

    /**
     * Tạo consumer factory khóa và giá trị kiểu chuỗi, đọc từ đầu khi nhóm chưa có offset.
     *
     * @param bootstrapServers địa chỉ broker Kafka
     * @param groupId nhóm consumer, lấy từ {@code notification.kafka.auth-otp-group}
     * @return consumer factory
     */
    @Bean
    ConsumerFactory<String, String> authOtpConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${notification.kafka.auth-otp-group:notification-auth-otp}") String groupId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("group.id", groupId);
        properties.put("auto.offset.reset", "earliest");
        properties.put("key.deserializer", StringDeserializer.class);
        properties.put("value.deserializer", StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    /**
     * Tạo container factory mặc định cho {@code @KafkaListener}, gắn error handler để thử lại và chuyển sang DLT.
     *
     * @param consumerFactory consumer factory dùng để đọc thông điệp
     * @param authOtpErrorHandler error handler khi xử lý lỗi
     * @return container factory của listener
     */
    @Bean(name = "kafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler authOtpErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(authOtpErrorHandler);
        return factory;
    }

    /**
     * Tạo error handler: thử lại 2 lần cách nhau 1 giây, nếu vẫn lỗi thì chuyển thông điệp sang topic DLT.
     *
     * @param kafkaTemplate template dùng để gửi vào DLT
     * @return error handler
     */
    @Bean
    DefaultErrorHandler authOtpErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), new FixedBackOff(1000L, 2L));
    }
}
