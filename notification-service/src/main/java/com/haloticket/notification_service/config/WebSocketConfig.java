package com.haloticket.notification_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Cấu hình WebSocket STOMP để đẩy thông báo thời gian thực xuống web (chuông thông báo). */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Bật broker đơn giản cho các đích {@code /topic} và {@code /queue}; tiền tố đích gửi từ client là {@code /app}.
     *
     * @param config bộ đăng ký message broker
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Đăng ký endpoint {@code /ws-notifications} cho mọi origin, một bản có SockJS dự phòng và một bản WebSocket thuần.
     *
     * @param registry bộ đăng ký endpoint STOMP
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-notifications")
                .setAllowedOriginPatterns("*")
                .withSockJS();
        registry.addEndpoint("/ws-notifications")
                .setAllowedOriginPatterns("*");
    }
}
