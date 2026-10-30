package com.haloticket.notification_service.authz;

import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;

class NotificationScopeGuardTest {

    UserServiceClient users = mock(UserServiceClient.class);
    NotificationRepository repository = mock(NotificationRepository.class);
    NotificationScopeProperties scope = new NotificationScopeProperties();
    NotificationScopeGuard guard = new NotificationScopeGuard(scope, users, repository);

    @BeforeEach
    void setUp() {
        scope.setInternalToken("service-secret");
        when(users.getOrganizerEligibility("Bearer alice")).thenReturn(new UserServiceClient.Eligibility(true, "u-alice"));
        signedInAs("kc-alice", "ROLE_CUSTOMER");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String subject, String role) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("sub", subject));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, "t", List.of(new SimpleGrantedAuthority(role))));
    }

    private static HttpStatus status(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    @Test
    void anInboxBelongsToTheSubjectOrTheUserServiceIdOfItsOwnerOnly() {
        assertThat(status(() -> guard.requireOwnerOfUserInbox("kc-alice", "Bearer alice"))).isEqualTo(HttpStatus.OK);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("u-alice", "Bearer alice"))).isEqualTo(HttpStatus.OK);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("kc-bob", "Bearer alice"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("u-bob", "Bearer alice"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("ADMIN", "Bearer alice"))).isEqualTo(HttpStatus.FORBIDDEN);
        SecurityContextHolder.clearContext();
        assertThat(status(() -> guard.requireOwnerOfUserInbox("kc-alice", null))).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAdminReadsAnyInboxIncludingTheSharedOne() {
        signedInAs("kc-root", "ROLE_ADMIN");
        assertThat(status(() -> guard.requireOwnerOfUserInbox("ADMIN", "Bearer root"))).isEqualTo(HttpStatus.OK);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("kc-bob", "Bearer root"))).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(users);
    }

    @Test
    void userServiceDownClosesAnInboxThatIsNotTheSubject() {
        when(users.getOrganizerEligibility("Bearer down")).thenThrow(new RuntimeException("connection refused"));
        assertThat(status(() -> guard.requireOwnerOfUserInbox("u-alice", "Bearer down"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status(() -> guard.requireOwnerOfUserInbox("kc-alice", "Bearer down"))).isEqualTo(HttpStatus.OK); // the subject needs no lookup
    }

    @Test
    void markingOneNotificationReadFollowsItsOwner() {
        Notification mine = new Notification();
        mine.setUserId("kc-alice");
        Notification theirs = new Notification();
        theirs.setUserId("kc-bob");
        when(repository.findById("n-mine")).thenReturn(Optional.of(mine));
        when(repository.findById("n-theirs")).thenReturn(Optional.of(theirs));
        when(repository.findById("n-none")).thenReturn(Optional.empty());
        assertThat(status(() -> guard.requireOwnerOfNotification("n-mine", "Bearer alice"))).isEqualTo(HttpStatus.OK);
        assertThat(status(() -> guard.requireOwnerOfNotification("n-theirs", "Bearer alice"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireOwnerOfNotification("n-none", "Bearer alice"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAndPublishingNeedTheServiceSecretOrAnAdmin() {
        assertThat(status(() -> guard.requireInternalOrAdmin(null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin("guess"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin("service-secret"))).isEqualTo(HttpStatus.OK);
        scope.setInternalToken("");
        assertThat(status(() -> guard.requireInternalOrAdmin(""))).isEqualTo(HttpStatus.FORBIDDEN);
        signedInAs("kc-root", "ROLE_ADMIN");
        assertThat(status(() -> guard.requireInternalOrAdmin(null))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aBlankInternalTokenRefusesCreatingAndPublishingEvenForABlankOrMissingHeader() {
        scope.setInternalToken("");
        assertThat(status(() -> guard.requireInternalOrAdmin(""))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin(null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin("service-secret"))).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
