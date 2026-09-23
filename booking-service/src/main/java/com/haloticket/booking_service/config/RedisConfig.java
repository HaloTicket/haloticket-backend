package com.haloticket.booking_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.RedisTemplate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisTemplate<String, String> redisTemplate;

    @PostConstruct
    public void enableKeyspaceNotifications() {
        try {
            redisTemplate.getConnectionFactory().getConnection().setConfig("notify-keyspace-events", "Ex");
        } catch (Exception e) {
            // might fail in some managed Redis environments, but we try anyway
            System.err.println("Could not configure Redis Keyspace notifications programmatically: " + e.getMessage());
        }
    }

    @Bean
    public RedisMessageListenerContainer keyExpirationListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisKeyExpirationListener expirationListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // listen for expiration events from any DB (0-15)
        container.addMessageListener(expirationListener, new PatternTopic("__keyevent@*__:expired"));
        
        return container;
    }
}
