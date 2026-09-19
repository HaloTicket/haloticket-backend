package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("kc-123")
            .claim("email", "user@example.com")
            .build();
    private MockMvc mockMvc;

    @Mock
    private com.haloticket.user_service.repository.UserRepository userRepository;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(userService, userRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new JwtArgumentResolver(jwt))
                .build();
    }

    @Test
    void getMeSyncsAuthenticatedJwt() throws Exception {
        when(userService.syncCurrentUser(jwt)).thenReturn(UserResponse.builder().keycloakId("kc-123").build());

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk());

        verify(userService).syncCurrentUser(jwt);
    }

    @Test
    void putMeUpdatesAuthenticatedProfile() throws Exception {
        when(userService.updateCurrentUser(eq(jwt), any(UserProfileUpdateRequest.class)))
                .thenReturn(UserResponse.builder().keycloakId("kc-123").build());

        mockMvc.perform(put("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"New Name\"}"))
                .andExpect(status().isOk());

        verify(userService).updateCurrentUser(eq(jwt), any(UserProfileUpdateRequest.class));
    }

    @Test
    void legacyCreateEndpointIsNotExposed() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyLookupEndpointIsNotExposed() throws Exception {
        mockMvc.perform(get("/api/users/kc-123"))
                .andExpect(status().isNotFound());
    }

    private static final class JwtArgumentResolver implements HandlerMethodArgumentResolver {
        private final Jwt jwt;

        private JwtArgumentResolver(Jwt jwt) {
            this.jwt = jwt;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                    && Jwt.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      org.springframework.web.context.request.NativeWebRequest webRequest,
                                      org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
            return jwt;
        }
    }
}
