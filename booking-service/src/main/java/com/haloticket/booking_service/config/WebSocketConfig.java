package com.haloticket.booking_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Cấu hình WebSocket STOMP: endpoint {@code /ws} và broker đơn giản để phát trạng thái ghế.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Đặt broker: {@code /topic} để phát thông báo, {@code /app} cho các phương thức {@code @MessageMapping}.
     *
     * @param config registry của message broker
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // dùng /topic để phát trạng thái ghế (ví dụ /topic/seats/{eventId})
        config.enableSimpleBroker("/topic");
        
        // dùng /app cho các tin nhắn gắn với phương thức có @MessageMapping
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Đăng ký endpoint {@code /ws} cho mọi origin, có SockJS dự phòng.
     *
     * @param registry registry của endpoint STOMP
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // dự phòng cho trình duyệt không hỗ trợ WebSocket
    }
}
