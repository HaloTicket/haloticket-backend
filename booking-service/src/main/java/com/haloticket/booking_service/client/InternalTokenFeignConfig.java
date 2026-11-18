package com.haloticket.booking_service.client;

import com.haloticket.booking_service.authz.EventScopeProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Cấu hình Feign gắn header {@code X-Internal-Token} vào mọi lời gọi service nội bộ.
 */
public class InternalTokenFeignConfig {

    /**
     * Tạo interceptor thêm {@code X-Internal-Token} vào request; bỏ qua khi token chưa được cấu hình.
     *
     * @param scope cấu hình chứa token nội bộ
     * @return interceptor của Feign
     */
    @Bean
    RequestInterceptor internalTokenInterceptor(EventScopeProperties scope) {
        return template -> {
            String token = scope.getInternalToken();
            if (!token.isBlank()) template.header("X-Internal-Token", token);
        };
    }
}
