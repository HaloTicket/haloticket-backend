package com.haloticket.event_service.service;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.enums.StaffRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Event-scoped authorization for event-service itself. Everything goes through {@link EventPermissions}, the single
 * function that reads staff_assignments (ACTIVE rows only); the caller's user-service id comes from {@link CallerIds}.
 */
@Service
@RequiredArgsConstructor
public class EventAccessService {
    private final EventPermissions permissions;
    private final CallerIds callers;

    public String actor(String authorization) {
        return callers.userId(authorization);
    }

    /** Whatever the caller may do with the event (never throws for "no permission"; 404 for an unknown event). */
    public EventAccess forCaller(String eventId, String authorization) {
        String userId = callers.userId(authorization);
        return permissions.forUser(userId, eventId, isPlatformAdmin());
    }

    /** Owner, admin or manager of the event; a platform admin passes too. */
    public StaffRole requireRead(String eventId, String authorization) {
        EventAccess access = forCaller(eventId, authorization);
        if (access.has(Permission.POLICY_READ)) return access.role();
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /** Owner or admin of the event, or a platform admin. Returns the caller's user-service id. */
    public String requireWrite(String eventId, String authorization) {
        String userId = callers.userId(authorization);
        EventAccess access = permissions.forUser(userId, eventId, isPlatformAdmin());
        if (access.has(Permission.POLICY_WRITE)) return userId;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    /** The caller must hold the permission for the event: 401 not signed in, 403 no permission, 404 unknown event. */
    public EventAccess require(String eventId, Permission permission, String authorization) {
        EventAccess access = permissions.forUser(callers.userId(authorization), eventId, isPlatformAdmin());
        if (!access.has(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return access;
    }

    /** Same, for controllers that do not take the Authorization header as a parameter. */
    public EventAccess requireForRequest(String eventId, Permission permission) {
        return require(eventId, permission, currentAuthorization());
    }

    /** ROLE_ADMIN of the platform. 401 when nobody is signed in. */
    public void requirePlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (!isPlatformAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    public static boolean isPlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private static String currentAuthorization() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader("Authorization");
        }
        return null;
    }
}
