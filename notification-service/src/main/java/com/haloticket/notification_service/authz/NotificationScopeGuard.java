package com.haloticket.notification_service.authz;

import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.repository.NotificationRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScopeGuard {

    private final NotificationScopeProperties scope;
    private final UserServiceClient users;
    private final NotificationRepository notifications;

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: service-to-service calls to this service "
                    + "(creating and publishing notifications) are refused until it is set to the shared secret");
        }
    }

    public void requireOwnerOfUserInbox(String userId, String authorization) {
        if (isAdmin()) return;
        String subject = subject();
        if (subject == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        if (subject.equals(userId)) return;
        // not the subject: it may still be the person's user-service id (notifications raised by event-service use it)
        String own;
        try {
            var eligibility = users.getOrganizerEligibility(authorization);
            own = eligibility == null ? null : eligibility.userId();
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
        }
        if (own == null || !own.equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_OWNER");
    }

    public void requireOwnerOfNotification(String notificationId, String authorization) {
        if (isAdmin()) return;
        var found = notifications.findById(notificationId);
        if (found.isEmpty()) return; // the controller answers "not found" as before
        requireOwnerOfUserInbox(found.get().getUserId(), authorization);
    }

    public void requireInternalOrAdmin(String internalToken) {
        if (isAdmin()) return;
        String configured = scope.getInternalToken();
        if (configured.isBlank() || internalToken == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
    }
}
