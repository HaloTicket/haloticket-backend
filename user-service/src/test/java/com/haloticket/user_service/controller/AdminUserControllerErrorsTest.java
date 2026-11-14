package com.haloticket.user_service.controller;

import com.haloticket.user_service.exception.GlobalExceptionHandler;
import com.haloticket.user_service.service.AdminUserManagementService;
import com.haloticket.user_service.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the web page receives: the status and the stable error code in "message". Security is covered by UserAdminAuthorizationTest. */
class AdminUserControllerErrorsTest {

    private final AdminUserManagementService management = mock(AdminUserManagementService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(mock(UserService.class), management))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("kc-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aDuplicateEmailComesBackAs409WithTheCode() throws Exception {
        when(management.createUser(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS"));
        mockMvc.perform(post("/api/users/admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.vn\",\"fullName\":\"A B\",\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("EMAIL_ALREADY_EXISTS")));
    }

    @Test
    void anUnknownRoleNameIsA400NotA500() throws Exception {
        mockMvc.perform(put("/api/users/admin/u1/roles").contentType(MediaType.APPLICATION_JSON).content("[\"SUPERUSER\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("INVALID_BODY")));
    }

    @Test
    void anUnknownStatusIsA400() throws Exception {
        mockMvc.perform(put("/api/users/admin/u1/status").param("status", "DELETED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aSuccessfulCreateIs201() throws Exception {
        mockMvc.perform(post("/api/users/admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.vn\",\"fullName\":\"A B\",\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isCreated());
    }
}
