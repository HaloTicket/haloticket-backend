package com.haloticket.event_service.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Sends the shared internal token (authz.event-scope.internal-token) to the services this one calls. */
public class InternalTokenFeignConfig {

    @Bean
    RequestInterceptor internalTokenInterceptor(@Value("${authz.event-scope.internal-token:}") String token) {
        return template -> {
            if (!token.isBlank()) template.header("X-Internal-Token", token);
        };
    }
}
