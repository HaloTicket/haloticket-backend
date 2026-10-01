package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class PaymentReportSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        when(outboxEventRepository.findSuccessfulPaymentFactsInRange(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(outboxEventRepository.countBookingsMissingConfirmationTimestamp()).thenReturn(0L);
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("admin-token",
                List.of("ROLE_USER", "ROLE_ADMIN"), List.of("ROLE_USER", "ROLE_ADMIN")));
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("user-token",
                List.of("ROLE_USER"), List.of("ROLE_USER")));
    }

    @Test
    void acceptsAdminRoleClaimWithExistingRolePrefix() throws Exception {
        mvc.perform(reportRequest().header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAuthenticatedNonAdmin() throws Exception {
        mvc.perform(reportRequest().header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnonymousRequest() throws Exception {
        mvc.perform(reportRequest()).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reportRequest() {
        return get("/api/payments/admin/report-payments")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-02T00:00:00Z");
    }

    private static Jwt jwt(String token, List<String> realmRoles, List<String> directRoles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token).header("alg", "none")
                .issuer("https://issuer.example").subject("admin")
                .issuedAt(now).expiresAt(now.plusSeconds(3600))
                .claim("realm_access", Map.of("roles", realmRoles))
                .claim("roles", directRoles).build();
    }
}
