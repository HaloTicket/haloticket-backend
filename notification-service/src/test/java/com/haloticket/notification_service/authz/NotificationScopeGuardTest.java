package com.haloticket.notification_service.authz;

import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.enums.NotificationVisibility;
import com.haloticket.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NotificationScopeGuardTest {

    private final UserServiceClient users = mock(UserServiceClient.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationScopeProperties scope = new NotificationScopeProperties();
    private final NotificationScopeGuard guard = new NotificationScopeGuard(scope, users, repository);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eitherVerifiedAliasOpensTheSameInbox() {
        signIn("kc-alice", "alice", "ROLE_CUSTOMER");
        when(users.getOrganizerEligibility("Bearer alice")).thenReturn(new UserServiceClient.Eligibility(false, "u-alice"));
        assertThat(guard.inboxAliases("kc-alice")).containsExactlyInAnyOrder("kc-alice", "u-alice");
        assertThat(guard.inboxAliases("u-alice")).containsExactlyInAnyOrder("kc-alice", "u-alice");
        assertThatThrownBy(() -> guard.inboxAliases("u-bob")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> guard.inboxAliases("ADMIN")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anAdminCanReadTheSharedInboxButDeletionOnlyUsesTheirOwnAliases() {
        signIn("kc-root", "root", "ROLE_ADMIN");
        when(users.getOrganizerEligibility("Bearer root")).thenReturn(new UserServiceClient.Eligibility(true, "u-root"));
        assertThat(guard.inboxAliases("ADMIN")).containsExactly("ADMIN");
        assertThat(guard.inboxAliases("kc-bob")).containsExactly("kc-bob");
        assertThat(guard.deleteAliases()).containsExactlyInAnyOrder("kc-root", "u-root");
        assertThat(guard.requireAdmin()).isEqualTo("kc-root");
    }

    @Test
    void platformAdminCannotReadAnotherUsersStaffInboxOrItem() {
        signIn("kc-root", "root", "ROLE_ADMIN");
        when(users.getOrganizerEligibility("Bearer root")).thenReturn(new UserServiceClient.Eligibility(true, "u-root"));
        when(repository.findById("staff-item")).thenReturn(Optional.of(Notification.builder().userId("u-bob")
                .visibility(NotificationVisibility.STAFF).build()));
        for (var channel : List.of(InboxChannel.STAFF, InboxChannel.ALL)) {
            assertThatThrownBy(() -> guard.inboxAliases("u-bob", channel)).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(guard.inboxAliases("u-root", channel)).containsExactlyInAnyOrder("kc-root", "u-root");
        }
        assertThatThrownBy(() -> guard.readableNotificationAliases("staff-item"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aDirectoryOutageLeavesTheSubjectAvailable() {
        signIn("kc-alice", "alice", "ROLE_CUSTOMER");
        when(users.getOrganizerEligibility("Bearer alice")).thenThrow(new RuntimeException("offline"));
        assertThat(guard.inboxAliases("kc-alice")).containsExactly("kc-alice");
        assertThatThrownBy(() -> guard.inboxAliases("u-alice")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void aForeignOrDeletedItemCannotBeRead() {
        signIn("kc-alice", "alice", "ROLE_CUSTOMER");
        when(repository.findById("foreign")).thenReturn(Optional.of(Notification.builder().userId("kc-bob").build()));
        when(repository.findById("deleted")).thenReturn(Optional.of(Notification.builder().userId("kc-alice")
                .deletedAt(Instant.now()).build()));
        assertThatThrownBy(() -> guard.readableNotificationAliases("foreign")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> guard.readableNotificationAliases("deleted")).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void internalProducerTokenCannotAuthorizeAnAnnouncement() {
        scope.setInternalToken("service-secret");
        guard.requireInternalOrAdmin("service-secret");
        assertThatThrownBy(guard::requireAdmin).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.requireInternalOrAdmin("guess")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aBlankInternalTokenRefusesCreatingAndPublishing() {
        assertThatThrownBy(() -> guard.requireInternalOrAdmin("")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.requireInternalOrAdmin(null)).isInstanceOf(ResponseStatusException.class);
    }

    private static void signIn(String subject, String token, String role) {
        var jwt = new Jwt(token, Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "test"), Map.of("sub", subject));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, token, List.of(new SimpleGrantedAuthority(role))));
    }
}
