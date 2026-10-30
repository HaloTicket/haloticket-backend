package com.haloticket.event_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The internal token (authz.event-scope.internal-token, env AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN) is what the other services
 * require on internal calls. Without it they refuse event-service; this service keeps running.
 */
@Slf4j
@Component
public class InternalTokenStartupCheck {

    private final String token;

    public InternalTokenStartupCheck(@Value("${authz.event-scope.internal-token:}") String token) {
        this.token = token;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportMissingToken() {
        if (token == null || token.isBlank()) {
            log.error("AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN (authz.event-scope.internal-token) is not set: internal calls from "
                    + "event-service to other services will be refused until it is set. The service keeps running.");
        }
    }
}
