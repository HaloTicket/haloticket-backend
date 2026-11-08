package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminListingControllerTest {
    private final TicketListingRepository repository = mock(TicketListingRepository.class);
    private final AdminListingController controller = new AdminListingController(repository);

    private static JwtAuthenticationToken token(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("u1").claims(c -> c.putAll(claims)).build();
        return new JwtAuthenticationToken(jwt, List.of());  // the 2-argument form is the authenticated one, as the real converter builds it
    }

    @Test
    void adminGetsTheRealCountAndOldestAge() {
        Instant oldest = Instant.parse("2026-11-01T00:00:00Z");
        when(repository.countByStatus(ListingStatus.PENDING_APPROVAL)).thenReturn(4L);
        when(repository.oldestCreatedAtByStatus(ListingStatus.PENDING_APPROVAL)).thenReturn(oldest);

        var summary = controller.pendingSummary(token(Map.of("roles", List.of("ROLE_USER", "ROLE_ADMIN"))));

        assertThat(summary.pendingCount()).isEqualTo(4);
        assertThat(summary.oldestPendingAt()).isEqualTo(oldest);
    }

    @Test
    void realmAccessRolesCountToo() {
        when(repository.countByStatus(ListingStatus.PENDING_APPROVAL)).thenReturn(0L);
        var summary = controller.pendingSummary(token(Map.of("realm_access", Map.of("roles", List.of("ADMIN")))));
        assertThat(summary.pendingCount()).isZero();
        assertThat(summary.oldestPendingAt()).isNull();
    }

    @Test
    void everybodyElseIsRefusedAndNothingIsRead() {
        assertThatThrownBy(() -> controller.pendingSummary(token(Map.of("roles", List.of("ROLE_USER")))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.pendingSummary(token(Map.of())))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.pendingSummary(new UsernamePasswordAuthenticationToken("x", "")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.pendingSummary(null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
}
