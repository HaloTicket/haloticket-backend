package com.haloticket.user_service.config;

import com.haloticket.user_service.controller.AdminUserController;
import com.haloticket.user_service.controller.InternalUserController;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.service.AdminUserManagementService;
import com.haloticket.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real SecurityConfig, JwtRoleConverter and controllers; only the service layer is a mock. Tokens are turned into an
 * authentication by the production converter, so a role claim has to survive the real mapping to pass.
 */
@SpringJUnitWebConfig(UserAdminAuthorizationTest.TestConfiguration.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/certs"})
class UserAdminAuthorizationTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Autowired WebApplicationContext context;
    @Autowired UserService userService;
    @Autowired AdminUserManagementService management;
    @Autowired JwtRoleConverter converter;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(userService, management);
        when(userService.getAdminUsers(any(), any(), any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void everyAdminEndpointIsUnauthorizedAnonymouslyForbiddenForOtherRolesAndOpenToAdmins() throws Exception {
        List<String[]> calls = List.of(
                new String[]{"GET", "/api/users/admin"},
                new String[]{"GET", "/api/users/admin/" + ID},
                new String[]{"GET", "/api/users/admin/stats"},
                new String[]{"GET", "/api/users/admin/audit"},
                new String[]{"POST", "/api/users/admin"},
                new String[]{"PUT", "/api/users/admin/" + ID + "/status?status=ACTIVE"},
                new String[]{"PUT", "/api/users/admin/" + ID + "/roles"});
        for (String[] call : calls) {
            mockMvc.perform(request(call[0], call[1], null)).andExpect(status().isUnauthorized());
            for (String role : List.of("ROLE_USER", "ROLE_ORGANIZER", "ROLE_STAFF")) {
                mockMvc.perform(request(call[0], call[1], token(role))).andExpect(status().isForbidden());
            }
            mockMvc.perform(request(call[0], call[1], token("ROLE_USER", "ROLE_ADMIN"))).andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void customerCannotChangeRolesOrStatusEvenWithAValidBody() throws Exception {
        mockMvc.perform(put("/api/users/admin/" + ID + "/roles").with(token("ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"ADMIN\"]"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/admin/" + ID + "/status").param("status", "ACTIVE").with(token("ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(management);
    }

    @Test
    void adminCanChangeRoles() throws Exception {
        mockMvc.perform(put("/api/users/admin/" + ID + "/roles").with(token("ROLE_USER", "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"CUSTOMER\",\"ADMIN\"]"))
                .andExpect(status().isOk());
        verify(management).updateRoles(any(), eq(ID), any());
    }

    @Test
    void trailingSlashVariantsDoNotSlipPast() throws Exception {
        for (String path : List.of("/api/users/admin/", "/api/users/admin/" + ID + "/")) {
            mockMvc.perform(get(path).with(token("ROLE_USER"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(userService);
    }

    @Test
    void internalEndpointRefusesToGrantAdmin() throws Exception {
        for (String role : List.of("ADMIN", "admin", "Admin")) {
            mockMvc.perform(put("/api/internal/users/" + ID + "/roles/" + role)).andExpect(status().isForbidden());
        }
        verify(userService, never()).addRoleToUser(anyString(), any());
    }

    @Test
    void internalEndpointStillGrantsTheOtherRolesAndRejectsUnknownOnes() throws Exception {
        mockMvc.perform(put("/api/internal/users/" + ID + "/roles/ORGANIZER")).andExpect(status().isOk());
        verify(userService).addRoleToUser(ID, Role.ORGANIZER);
        mockMvc.perform(put("/api/internal/users/" + ID + "/roles/organizer")).andExpect(status().isOk());
        mockMvc.perform(put("/api/internal/users/" + ID + "/roles/NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryAuthenticatedEndpointsAreUnchanged() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor token(String... roles) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("kc-user").claim("roles", List.of(roles)).build();
        return authentication(converter.convert(jwt));
    }

    private MockHttpServletRequestBuilder request(String method, String path, RequestPostProcessor token) {
        MockHttpServletRequestBuilder builder = method.equals("PUT")
                ? put(path).contentType(MediaType.APPLICATION_JSON).content("[\"CUSTOMER\"]")
                : method.equals("POST")
                ? post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                : get(path);
        return token == null ? builder : builder.with(token);
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtRoleConverter.class, AdminUserController.class, InternalUserController.class})
    static class TestConfiguration {
        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        AdminUserManagementService adminUserManagementService() {
            return mock(AdminUserManagementService.class);
        }
    }
}
