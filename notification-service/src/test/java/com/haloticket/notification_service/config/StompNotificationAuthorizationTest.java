package com.haloticket.notification_service.config;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.authz.NotificationScopeProperties;
import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StompNotificationAuthorizationTest {
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final UserServiceClient users = mock(UserServiceClient.class);
    private final StompNotificationAuthorization authorization = new StompNotificationAuthorization(decoder,
            new NotificationScopeGuard(new NotificationScopeProperties(), users, mock(NotificationRepository.class)));

    @Test
    void connectRequiresNativeBearerEvenWithAnHttpPrincipal() {
        var headers = frame(StompCommand.CONNECT);
        headers.setUser(SecurityConfig.authentication(jwt("customer")));
        assertThatThrownBy(() -> send(headers)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(decoder);
    }

    @Test
    void userMaySubscribeOnlyToTheirVerifiedAliasesAndCannotSendToTheBroker() {
        var session = connect("customer");
        subscribe(session, "/topic/user-notifications/kc-alice");
        subscribe(session, "/topic/user-notifications/u-alice");
        subscribe(session, "/topic/staff-notifications/u-alice");
        subscribe(session, "/topic/staff-notifications/kc-alice");

        for (String destination : List.of("/topic/user-notifications/u-bob", "/topic/user-notifications/**",
                "/topic/user-notifications/ADMIN", "/topic/admin-notifications",
                "/topic/staff-notifications/u-bob", "/topic/staff-notifications/other/u-alice")) {
            assertThatThrownBy(() -> subscribe(session, destination)).isInstanceOf(AccessDeniedException.class);
        }
        var forged = frame(StompCommand.SEND);
        forged.setUser(session.getUser());
        forged.setDestination("/topic/user-notifications/u-alice");
        assertThatThrownBy(() -> send(forged)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminMaySubscribeToTheSharedTopicAndOnlyTheirOwnPersonalTopics() {
        var session = connect("admin");
        subscribe(session, "/topic/admin-notifications");
        subscribe(session, "/topic/user-notifications/u-alice");
        assertThatThrownBy(() -> subscribe(session, "/topic/user-notifications/u-bob"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private StompHeaderAccessor connect(String role) {
        when(decoder.decode("token")).thenReturn(jwt(role));
        when(users.getOrganizerEligibility("Bearer token")).thenReturn(new UserServiceClient.Eligibility(false, "u-alice"));
        var headers = frame(StompCommand.CONNECT);
        headers.setNativeHeader("Authorization", "Bearer token");
        send(headers);
        return headers;
    }

    private void subscribe(StompHeaderAccessor session, String destination) {
        var headers = frame(StompCommand.SUBSCRIBE);
        headers.setSessionAttributes(session.getSessionAttributes());
        headers.setUser(session.getUser());
        headers.setDestination(destination);
        send(headers);
    }

    private void send(StompHeaderAccessor headers) {
        authorization.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null);
    }

    private static StompHeaderAccessor frame(StompCommand command) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionAttributes(new HashMap<>());
        headers.setLeaveMutable(true);
        return headers;
    }

    private static Jwt jwt(String role) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(120),
                Map.of("alg", "test"), Map.of("sub", "kc-alice", "roles", List.of(role)));
    }
}
