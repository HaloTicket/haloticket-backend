package com.haloticket.event_service.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Gửi token nội bộ dùng chung (authz.event-scope.internal-token) tới các service mà service này gọi. */
public class InternalTokenFeignConfig {

    /** Interceptor thêm header {@code X-Internal-Token} vào mọi request Feign khi đã cấu hình token. */
    @Bean
    RequestInterceptor internalTokenInterceptor(@Value("${authz.event-scope.internal-token:}") String token) {
        return template -> {
            if (!token.isBlank()) template.header("X-Internal-Token", token);
        };
    }
}
