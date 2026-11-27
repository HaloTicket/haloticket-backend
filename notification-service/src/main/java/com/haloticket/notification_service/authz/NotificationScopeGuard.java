package com.haloticket.notification_service.authz;

import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.enums.NotificationVisibility;
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
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScopeGuard {
    public static final String SHARED_ADMIN = "ADMIN";

    private final NotificationScopeProperties scope;
    private final UserServiceClient users;
    private final NotificationRepository notifications;

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank. Internal notification and directory calls are refused.");
        }
    }

    public Set<String> inboxAliases(String userId) {
        return inboxAliases(userId, InboxChannel.PUBLIC);
    }

    public Set<String> inboxAliases(String userId, InboxChannel channel) {
        Authentication auth = currentAuth();
        String subject = requireSubject(auth);
        if (SHARED_ADMIN.equalsIgnoreCase(userId)) {
            if (channel == InboxChannel.PUBLIC && isAdmin(auth)) return Set.of(SHARED_ADMIN);
            throw forbiddenOwner();
        }
        boolean admin = channel == InboxChannel.PUBLIC && isAdmin(auth);
        Set<String> aliases = personalAliases(auth, admin || subject.equals(userId));
        if (aliases.contains(userId)) return aliases;
        if (admin) return Set.of(userId);
        throw forbiddenOwner();
    }

    public Set<String> readableNotificationAliases(String id) {
        requireSubject(currentAuth());
        var notification = notifications.findById(id)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND"));
        return inboxAliases(notification.getUserId(), notification.getVisibility() == NotificationVisibility.STAFF
                ? InboxChannel.STAFF : InboxChannel.PUBLIC);
    }

    public Set<String> deleteAliases() {
        return personalAliases(currentAuth());
    }

    public Set<String> personalAliases(Authentication auth) {
        return personalAliases(auth, true);
    }

    private Set<String> personalAliases(Authentication auth, boolean allowSubjectOnly) {
        String subject = requireSubject(auth);
        Set<String> aliases = new LinkedHashSet<>();
        if (!SHARED_ADMIN.equalsIgnoreCase(subject)) aliases.add(subject);
        try {
            String id = verifiedUserId(auth);
            if (id != null && !id.isBlank() && !SHARED_ADMIN.equalsIgnoreCase(id)) aliases.add(id);
        } catch (ResponseStatusException exception) {
            if (!allowSubjectOnly || exception.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
            // A verified JWT subject is available even if user-service is temporarily down.
            log.debug("Could not load user-service alias for authenticated notification session");
        }
        return Set.copyOf(aliases);
    }

    private String verifiedUserId(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        try {
            var account = users.getOrganizerEligibility("Bearer " + jwt.getTokenValue());
            return account == null ? null : account.userId();
        } catch (FeignException.NotFound exception) {
            return null;
        } catch (FeignException.Unauthorized exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE", exception);
        }
    }

    public String requireAdmin() {
        Authentication auth = currentAuth();
        if (!isAdmin(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        return requireSubject(auth);
    }

    public void requireInternalOrAdmin(String internalToken) {
        if (isAdmin(currentAuth())) return;
        String configured = scope.getInternalToken();
        if (configured.isBlank() || internalToken == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    public static boolean isAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static String requireSubject(Authentication auth) {
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    private static ResponseStatusException forbiddenOwner() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_OWNER");
    }
}
