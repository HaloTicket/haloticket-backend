package com.haloticket.payment_service.authz;

import com.haloticket.payment_service.client.BookingServiceClient;
import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentScopeGuardTest {

    UserServiceClient users = mock(UserServiceClient.class);
    BookingServiceClient bookings = mock(BookingServiceClient.class);
    PaymentScopeProperties scope = new PaymentScopeProperties();
    PaymentScopeGuard guard = new PaymentScopeGuard(scope, users, bookings);

    @BeforeEach
    void setUp() {
        scope.setEnabled(true);
        scope.getIdentity().setEnabled(true);
        scope.setInternalToken("service-secret");
        when(users.getOrganizerEligibility("Bearer owner")).thenReturn(new OrganizerEligibilityResponse(true, "u-owner", null, null));
        when(users.getOrganizerEligibility("Bearer other")).thenReturn(new OrganizerEligibilityResponse(false, "u-other", null, null));
        when(bookings.getBooking("bk-1", "Bearer buyer")).thenReturn(new BookingServiceClient.BookingOwner("bk-1", "kc-buyer"));
        when(bookings.getBooking("bk-1", "Bearer stranger")).thenReturn(new BookingServiceClient.BookingOwner("bk-1", "kc-buyer"));
        signedInAs("kc-buyer", "ROLE_CUSTOMER");
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
    void aPayoutAccountIsReadByItsOwnerOrAnAdmin() {
        assertThat(status(() -> guard.requirePayoutAccountOwner("u-owner", null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(() -> guard.requirePayoutAccountOwner("u-owner", "Bearer owner"))).isEqualTo(HttpStatus.OK);
        assertThat(status(() -> guard.requirePayoutAccountOwner("u-owner", "Bearer other"))).isEqualTo(HttpStatus.FORBIDDEN);
        signedInAs("kc-root", "ROLE_ADMIN");
        assertThat(status(() -> guard.requirePayoutAccountOwner("u-owner", "Bearer root"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void userServiceDownClosesThePayoutAccount() {
        when(users.getOrganizerEligibility("Bearer down")).thenThrow(new RuntimeException("connection refused"));
        assertThat(status(() -> guard.requirePayoutAccountOwner("u-owner", "Bearer down"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aPaymentIsShownToTheBuyerOfTheBookingOnly() {
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", null))).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", "Bearer buyer"))).isEqualTo(HttpStatus.OK);
        signedInAs("kc-stranger", "ROLE_CUSTOMER");
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", "Bearer stranger"))).isEqualTo(HttpStatus.NOT_FOUND);
        when(bookings.getBooking("bk-1", "Bearer missing")).thenThrow(new FeignException.NotFound("no",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, StandardCharsets.UTF_8, null), new byte[0], Map.of()));
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", "Bearer missing"))).isEqualTo(HttpStatus.NOT_FOUND);
        when(bookings.getBooking("bk-1", "Bearer down")).thenThrow(new RuntimeException("connection refused"));
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", "Bearer down"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        signedInAs("kc-root", "ROLE_ADMIN");
        assertThat(status(() -> guard.requireBookingBuyer("bk-1", "Bearer root"))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theBulkLookupNeedsTheServiceSecretOrAnAdmin() {
        assertThat(status(() -> guard.requireInternalOrAdmin(null))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin("guess"))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(() -> guard.requireInternalOrAdmin("service-secret"))).isEqualTo(HttpStatus.OK);
        scope.setInternalToken("");
        assertThat(status(() -> guard.requireInternalOrAdmin(""))).isEqualTo(HttpStatus.FORBIDDEN);
        signedInAs("kc-root", "ROLE_ADMIN");
        assertThat(status(() -> guard.requireInternalOrAdmin(null))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withTheSwitchOffNothingIsChecked() {
        scope.setEnabled(false);
        SecurityContextHolder.clearContext();
        assertThatCode(() -> {
            guard.requirePayoutAccountOwner("anyone", null);
            guard.requireBookingBuyer("bk-1", null);
            guard.requireInternalOrAdmin(null);
        }).doesNotThrowAnyException();
        verifyNoInteractions(users, bookings);
    }
}
