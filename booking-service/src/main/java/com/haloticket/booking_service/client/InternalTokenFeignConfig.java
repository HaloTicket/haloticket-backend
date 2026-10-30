package com.haloticket.booking_service.client;

import com.haloticket.booking_service.authz.EventScopeProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

public class InternalTokenFeignConfig {

    @Bean
    RequestInterceptor internalTokenInterceptor(EventScopeProperties scope) {
        return template -> {
            String token = scope.getInternalToken();
            if (!token.isBlank()) template.header("X-Internal-Token", token);
        };
    }
}
