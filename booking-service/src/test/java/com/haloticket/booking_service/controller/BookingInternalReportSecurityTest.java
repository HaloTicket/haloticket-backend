package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class BookingInternalReportSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean BookingRepository bookingRepository;
    @MockitoBean DataInitializer dataInitializer;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RedissonClient redissonClient;
    @MockitoBean RedisMessageListenerContainer keyExpirationListenerContainer;

    @Test
    void rejectsAnonymousReportFactsRequest() throws Exception {
        mvc.perform(post("/api/internal/bookings/report-facts").contentType(APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsCustomerReportFactsRequest() throws Exception {
        when(jwtDecoder.decode("valid-customer-token")).thenReturn(token("valid-customer-token", "ROLE_CUSTOMER"));
        mvc.perform(post("/api/internal/bookings/report-facts")
                        .header("Authorization", "Bearer valid-customer-token")
                        .contentType(APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void permitsAdminAndLeavesOtherBookingRoutesPublic() throws Exception {
        when(jwtDecoder.decode("valid-admin-token")).thenReturn(token("valid-admin-token", "ROLE_ADMIN"));
        mvc.perform(post("/api/internal/bookings/report-facts")
                        .header("Authorization", "Bearer valid-admin-token")
                        .contentType(APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/internal/bookings/events/event-1/stats").header("Authorization", "Bearer deliberately-invalid"))
                .andExpect(status().isOk());
        verify(jwtDecoder, never()).decode("deliberately-invalid");
    }

    private static Jwt token(String value, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value).header("alg", "none").issuer("https://issuer.example")
                .subject("test-user").issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of(role))).build();
    }
}
