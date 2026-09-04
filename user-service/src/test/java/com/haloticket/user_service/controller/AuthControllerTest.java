package com.haloticket.user_service.controller;

import com.haloticket.user_service.auth.service.AuthApplicationService;
import com.haloticket.user_service.auth.service.AuthOtpChallengeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {
    private final AuthApplicationService authService = mock(AuthApplicationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var challenge = com.haloticket.user_service.auth.domain.AuthOtpChallenge.builder().id("challenge-1")
                .subject("user@example.com")
                .expiresAt(java.time.Instant.parse("2026-09-04T10:05:00Z"))
                .resendAvailableAt(java.time.Instant.parse("2026-09-04T10:01:00Z"))
                .build();
        when(authService.startRegistration(any())).thenReturn(new AuthOtpChallengeService.CreateResult(challenge, null));
        when(authService.resendRegistration(any())).thenReturn(new AuthOtpChallengeService.CreateResult(challenge, null));
        when(authService.startPasswordReset(any())).thenReturn(new AuthOtpChallengeService.CreateResult(challenge, null));
        when(authService.resendPasswordReset(any())).thenReturn(new AuthOtpChallengeService.CreateResult(challenge, null));
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService)).build();
    }

    @Test
    void registerStartReturnsChallengeMetadataWithoutOtp() throws Exception {
        when(authService.startRegistration(any())).thenReturn(new AuthOtpChallengeService.CreateResult(
                com.haloticket.user_service.auth.domain.AuthOtpChallenge.builder().id("challenge-1")
                        .expiresAt(java.time.Instant.parse("2026-09-04T10:05:00Z"))
                        .resendAvailableAt(java.time.Instant.parse("2026-09-04T10:01:00Z")).build(), "123456"));

        mockMvc.perform(post("/api/auth/register/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Password1!\",\"idempotencyKey\":\"req-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").value("challenge-1"))
                .andExpect(jsonPath("$.otp").doesNotExist());
    }

    @Test
    void registerStartRejectsInvalidPasswordBeforeService() throws Exception {
        mockMvc.perform(post("/api/auth/register/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"weak\",\"idempotencyKey\":\"req-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordForgotAcceptsOneEmailOrPhoneIdentifier() throws Exception {
        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"0912345678\",\"idempotencyKey\":\"req-1\"}"))
                .andExpect(status().isAccepted());

        verify(authService).startPasswordReset(new com.haloticket.user_service.auth.service.AuthRequests.PasswordForgot(
                "0912345678", "req-1", "anonymous"));
    }

    @Test
    void allAuthContractRoutesAreMapped() throws Exception {
        for (String path : new String[]{"/api/auth/register/verify", "/api/auth/register/resend",
                "/api/auth/password/forgot", "/api/auth/password/verify", "/api/auth/password/resend",
                "/api/auth/password/reset", "/api/auth/login"}) {
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                            .isNotEqualTo(404));
        }
    }
}
