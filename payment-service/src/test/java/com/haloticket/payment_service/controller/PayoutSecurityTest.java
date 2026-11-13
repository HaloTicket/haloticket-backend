package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.config.SecurityConfig;
import com.haloticket.payment_service.service.PayoutService;
import com.haloticket.payment_service.repository.PayoutRepository;
import com.haloticket.payment_service.repository.PayoutReconciliationRepository;
import com.haloticket.payment_service.repository.OrganizerPayoutAccountRepository;
import com.haloticket.payment_service.client.EventServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PayoutController.class)
@Import({SecurityConfig.class, PayoutSecurityTest.WebSecurityTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class PayoutSecurityTest {
    @TestConfiguration
    @EnableWebSecurity
    static class WebSecurityTestConfig {}

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PayoutService payoutService;
    @MockitoBean PayoutRepository payoutRepository;
    @MockitoBean PayoutReconciliationRepository reconciliationRepository;
    @MockitoBean EventServiceClient eventServiceClient;
    @MockitoBean OrganizerPayoutAccountRepository payoutAccountRepository;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("admin-token", "admin", List.of("ADMIN")));
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("user-token", "organizer-1", List.of("USER")));
        when(payoutService.getPendingPayouts()).thenReturn(List.of());
        when(payoutService.resolveOrganizerId("Bearer user-token")).thenReturn("organizer-1");
        when(payoutService.getOrganizerPayouts("organizer-1")).thenReturn(List.of());
    }

    @Test
    void adminQueueRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/payouts/admin/pending").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payouts/admin/pending").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void organizerReadsAreScopedToAuthenticatedPrincipal() throws Exception {
        mvc.perform(get("/api/payouts/organizer").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
        verify(payoutService).getOrganizerPayouts("organizer-1");
    }

    private static Jwt jwt(String token, String subject, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token).header("alg", "none").issuer("https://issuer.example")
                .subject(subject).issuedAt(now).expiresAt(now.plusSeconds(3600))
                .claim("realm_access", Map.of("roles", roles)).build();
    }
}
