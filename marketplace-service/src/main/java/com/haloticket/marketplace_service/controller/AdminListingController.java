package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Read-only counters for the admin dashboard. The gateway already limits /api/marketplace/admin/** to ROLE_ADMIN; the role
 * is checked again here because this service has no role mapping of its own and must not trust the network path alone.
 */
@RestController
@RequestMapping("/api/marketplace/admin")
public class AdminListingController {
    public record PendingSummary(long pendingCount, Instant oldestPendingAt) {}

    private final TicketListingRepository listings;

    public AdminListingController(TicketListingRepository listings) {
        this.listings = listings;
    }

    @GetMapping("/listings/summary")
    public PendingSummary pendingSummary(Authentication authentication) {
        requireAdmin(authentication);
        return new PendingSummary(listings.countByStatus(ListingStatus.PENDING_APPROVAL),
                listings.oldestCreatedAtByStatus(ListingStatus.PENDING_APPROVAL));
    }

    static void requireAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_ONLY");
        }
        Object direct = token.getToken().getClaim("roles");
        Object realm = token.getToken().getClaim("realm_access");
        boolean admin = hasAdmin(direct) || (realm instanceof Map<?, ?> map && hasAdmin(map.get("roles")));
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_ONLY");
        }
    }

    private static boolean hasAdmin(Object roles) {
        return roles instanceof Collection<?> values && values.stream()
                .anyMatch(v -> "ADMIN".equalsIgnoreCase(String.valueOf(v)) || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(v)));
    }
}
