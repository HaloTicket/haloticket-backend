package com.haloticket.booking_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.RedisTemplate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Cấu hình Redis: bật thông báo keyspace và đăng ký bộ lắng nghe khóa hết hạn.
 */
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Bật {@code notify-keyspace-events=Ex} để Redis phát sự kiện khóa hết hạn. Lỗi (ví dụ Redis được quản lý không cho đổi) chỉ được in ra, không dừng ứng dụng.
     */
    @PostConstruct
    public void enableKeyspaceNotifications() {
        try {
            redisTemplate.getConnectionFactory().getConnection().setConfig("notify-keyspace-events", "Ex");
        } catch (Exception e) {
            // có thể thất bại ở một số môi trường Redis được quản lý, nhưng vẫn thử
            System.err.println("Could not configure Redis Keyspace notifications programmatically: " + e.getMessage());
        }
    }

    /**
     * Tạo container lắng nghe sự kiện khóa hết hạn ({@code __keyevent@*__:expired}) và chuyển cho {@code expirationListener}.
     *
     * @param connectionFactory factory kết nối Redis
     * @param expirationListener bộ xử lý khi khóa hết hạn
     * @return container lắng nghe
     */
    @Bean
    public RedisMessageListenerContainer keyExpirationListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisKeyExpirationListener expirationListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // lắng nghe sự kiện hết hạn từ mọi DB (0-15)
        container.addMessageListener(expirationListener, new PatternTopic("__keyevent@*__:expired"));
        
        return container;
    }
}
