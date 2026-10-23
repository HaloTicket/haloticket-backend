package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.config.BookingJwtRoleConverter;
import com.haloticket.booking_service.config.InternalReportSecurityConfig;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.exception.GlobalExceptionHandler;
import com.haloticket.booking_service.service.BookingCancellationService;
import com.haloticket.booking_service.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/bookings/{id}/cancel needs a login and only ever acts for the token's own subject. */
@WebMvcTest(controllers = BookingController.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@Import({InternalReportSecurityConfig.class, BookingJwtRoleConverter.class, GlobalExceptionHandler.class})
class BookingCancelEndpointTest {

    @TestConfiguration
    @EnableWebSecurity
    static class WebSecurityEnabled {}

    @Autowired MockMvc mvc;
    @MockitoBean BookingService bookingService;
    @MockitoBean BookingCancellationService cancellation;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("user-token", "keycloak-user-1"));
    }

    private static Jwt jwt(String value, String subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value).header("alg", "none").issuer("https://issuer.example").subject(subject)
                .issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of("ROLE_CUSTOMER"))).build();
    }

    @Test
    void anonymousCannotCancel() throws Exception {
        mvc.perform(post("/api/bookings/b1/cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(cancellation);
    }

    @Test
    void loggedInUserCancelsAsTheirOwnSubject() throws Exception {
        when(cancellation.cancelOwnBooking("b1", "keycloak-user-1")).thenReturn(
                BookingResponse.builder().id("b1").status(BookingStatus.CANCELLED).build());

        mvc.perform(post("/api/bookings/b1/cancel").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(cancellation).cancelOwnBooking("b1", "keycloak-user-1");
    }

    @Test
    void someoneElsesBookingIs404() throws Exception {
        when(cancellation.cancelOwnBooking("b2", "keycloak-user-1"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        mvc.perform(post("/api/bookings/b2/cancel").header("Authorization", "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }
}
