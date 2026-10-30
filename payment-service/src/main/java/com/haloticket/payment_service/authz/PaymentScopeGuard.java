package com.haloticket.payment_service.authz;

import com.haloticket.payment_service.client.BookingServiceClient;
import com.haloticket.payment_service.client.UserServiceClient;
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

/**
 * Identity checks of the event-scoped authorization (docs/event-authz-plan.md) for payment-service. They always run; a blank
 * internal token refuses every service-to-service call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScopeGuard {

    private final PaymentScopeProperties scope;
    private final UserServiceClient users;
    private final BookingServiceClient bookings;

    @EventListener(ApplicationReadyEvent.class)
    void warnWhenInternalTokenIsBlank() {
        if (scope.getInternalToken().isBlank()) {
            log.error("[INTERNAL_TOKEN_MISSING] AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN is blank: service-to-service calls to this service "
                    + "(bulk payment-method lookup from booking-service) are refused until it is set to the shared secret");
        }
    }

    /** A payout account (bank details) is read by its owner, whose user-service id is the path id, or by a platform admin. */
    public void requirePayoutAccountOwner(String pathUserId, String authorization) {
        if (isAdmin()) return;
        requireToken(authorization);
        if ("me".equals(pathUserId)) return;
        String own;
        try {
            var eligibility = users.getOrganizerEligibility(authorization);
            own = eligibility == null ? null : eligibility.userId();
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USER_SERVICE_UNAVAILABLE");
        }
        if (own == null || !own.equals(pathUserId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_OWNER");
    }

    /** The payment of a booking is shown to the buyer of that booking or to a platform admin. */
    public void requireBookingBuyer(String bookingId, String authorization) {
        if (isAdmin()) return;
        requireToken(authorization);
        String subject = subject();
        String customer;
        try {
            var booking = bookings.getBooking(bookingId, authorization);
            customer = booking == null ? null : booking.customerId();
        } catch (FeignException.NotFound | FeignException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
        } catch (FeignException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE");
        }
        if (subject == null || !subject.equals(customer)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
    }

    /** The bulk payment-method lookup is for booking-service (shared secret) and platform admins. */
    public void requireInternalOrAdmin(String internalToken) {
        if (isAdmin()) return;
        String configured = scope.getInternalToken();
        if (configured.isBlank() || internalToken == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8), internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private static void requireToken(String authorization) {
        if (authorization == null || authorization.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
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
