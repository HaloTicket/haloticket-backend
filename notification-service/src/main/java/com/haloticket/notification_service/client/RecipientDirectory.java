package com.haloticket.notification_service.client;

import com.haloticket.notification_service.authz.NotificationScopeProperties;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecipientDirectory {
    private final NotificationScopeProperties scope;
    private final AnnouncementDirectoryClient users;
    private final EventTeamClient events;
    private final TicketOwnerClient tickets;

    public String canonicalIdOrAlias(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Missing notification recipient");
        }
        if ("ADMIN".equalsIgnoreCase(identifier)) return "ADMIN";
        try {
            return users.resolve(scope.getInternalToken(), identifier).id();
        } catch (FeignException.NotFound exception) {
            return identifier;
        } catch (FeignException exception) {
            // Legacy publishers may already have a canonical ID and need to keep working during a directory outage.
            log.warn("Notification recipient resolution unavailable (HTTP {})", exception.status());
            return identifier;
        }
    }

    public String activeAccount(String userId, Instant cutoff) {
        return users.activeAccount(scope.getInternalToken(), userId, cutoff).id();
    }

    public List<String> activeAccounts(Instant cutoff, String after, int limit) {
        return users.activeIds(scope.getInternalToken(), cutoff, after, limit);
    }

    public List<String> activeMatches(Instant cutoff, List<String> ids) {
        return ids.isEmpty() ? List.of() : users.matchActiveIds(scope.getInternalToken(), cutoff, ids);
    }

    public List<String> activeBuyerMatches(Instant cutoff, List<String> ids) {
        return ids.isEmpty() ? List.of() : users.matchActiveBuyerIds(scope.getInternalToken(), cutoff, ids);
    }

    public List<String> eventTeam(String eventId, Instant cutoff, String after, int limit) {
        return events.teamIds(scope.getInternalToken(), eventId, cutoff, after, limit);
    }

    public List<TicketOwnerClient.Ownership> ticketOwners(String eventId, String actorId, String ticketTypeId,
                                                       String showtimeId, String after, int limit) {
        return tickets.owners(scope.getInternalToken(), eventId, actorId, ticketTypeId, showtimeId, after, limit);
    }

    public List<String> announcementStaff(String eventId, String actorId, Instant cutoff, String role,
                                         String memberId, String after, int limit) {
        return events.announcementStaff(scope.getInternalToken(), eventId, actorId, cutoff, role, memberId, after, limit);
    }

    public void requireAnnouncementAccess(String eventId, String actorId) {
        events.requireAnnouncementAccess(scope.getInternalToken(), eventId, actorId);
    }
}
