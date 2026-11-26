package com.haloticket.notification_service.config;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StompNotificationAuthorization implements ChannelInterceptor {
    private static final String ADMIN_TOPIC = "/topic/admin-notifications";
    private static final String PERSONAL_TOPIC = "/topic/user-notifications/";
    private static final String STAFF_TOPIC = "/topic/staff-notifications/";
    private static final String ALIASES = StompNotificationAuthorization.class.getName() + ".aliases";

    private final JwtDecoder jwtDecoder;
    private final NotificationScopeGuard scopes;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null) return message;
        switch (headers.getCommand()) {
            case CONNECT, STOMP -> connect(headers);
            case SUBSCRIBE -> subscribe(headers);
            case UNSUBSCRIBE, DISCONNECT -> { }
            default -> throw denied();
        }
        return message;
    }

    private void connect(StompHeaderAccessor headers) {
        List<String> values = headers.getNativeHeader("Authorization");
        if (values == null || values.size() != 1 || !values.getFirst().startsWith("Bearer ")) {
            throw denied();
        }
        var jwt = jwtDecoder.decode(values.getFirst().substring("Bearer ".length()));
        var authentication = SecurityConfig.authentication(jwt);
        Set<String> aliases = scopes.personalAliases(authentication);
        Objects.requireNonNull(headers.getSessionAttributes()).put(ALIASES, aliases);
        headers.setUser(authentication);
    }

    private static void subscribe(StompHeaderAccessor headers) {
        if (!(headers.getUser() instanceof JwtAuthenticationToken auth) || auth.getToken().getExpiresAt() == null
                || !auth.getToken().getExpiresAt().isAfter(Instant.now())) {
            throw denied();
        }
        String destination = headers.getDestination();
        if (ADMIN_TOPIC.equals(destination) && NotificationScopeGuard.isAdmin(auth)) return;
        String prefix = destination != null && destination.startsWith(STAFF_TOPIC) ? STAFF_TOPIC : PERSONAL_TOPIC;
        if (destination != null && destination.startsWith(prefix)) {
            Object aliases = Objects.requireNonNull(headers.getSessionAttributes()).get(ALIASES);
            String target = destination.substring(prefix.length());
            if (aliases instanceof Set<?> verified && verified.contains(target)) return;
        }
        throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Notification STOMP access denied");
    }
}
