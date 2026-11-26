package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.authz.NotificationScopeProperties;
import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.dto.response.AnnouncementResponse;
import com.haloticket.notification_service.entity.AdminAnnouncement.Status;
import com.haloticket.notification_service.exception.GlobalExceptionHandler;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.service.AdminAnnouncementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAnnouncementControllerTest {
    private static final String URL = "/api/notifications/admin/announcements";
    private static final String REQUEST = """
            {"requestId":"1837f618-28a8-4cb4-aa49-fb1d15f68030","title":"Title","message":"Message",
             "priority":"NORMAL","audience":{"kind":"ALL"}}
            """;
    private final AdminAnnouncementService announcements = mock(AdminAnnouncementService.class);
    private final NotificationScopeProperties properties = new NotificationScopeProperties();
    private final NotificationScopeGuard scopes = new NotificationScopeGuard(properties,
            mock(UserServiceClient.class), mock(NotificationRepository.class));
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AdminAnnouncementController(scopes, announcements))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void internalTokenAndCustomerCannotSubmitOrPoll() throws Exception {
        properties.setInternalToken("internal-secret");
        mvc.perform(post(URL).header("X-Internal-Token", "internal-secret")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        signIn("ROLE_CUSTOMER");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/job")).andExpect(status().isForbidden());
        verifyNoInteractions(announcements);
    }

    @Test
    void adminSubmitsAndPollsUsingTheJwtSubject() throws Exception {
        signIn("ROLE_ADMIN");
        when(announcements.enqueue(eq("actor-1"), any())).thenReturn(new AnnouncementResponse("job", Status.QUEUED, 0, 0, null));
        when(announcements.get("job")).thenReturn(new AnnouncementResponse("job", Status.COMPLETED, 2, 2, null));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("job"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get(URL + "/job")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.recipientCount").value(2)).andExpect(jsonPath("$.deliveredCount").value(2));
    }

    @Test
    void invalidEnumReturnsBadRequest() throws Exception {
        signIn("ROLE_ADMIN");
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(REQUEST.replace("NORMAL", "INVALID")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(announcements);
    }

    @Test
    void adminComposeRejectsBuyerAudience() throws Exception {
        signIn("ROLE_ADMIN");
        var controller = new AdminAnnouncementController(scopes, new AdminAnnouncementService(
                mock(com.haloticket.notification_service.repository.AnnouncementStore.class)));
        var localMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        localMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("\"kind\":\"ALL\"", "\"kind\":\"EVENT_BUYERS\",\"eventId\":\"event\"")))
                .andExpect(status().isBadRequest());
    }

    private static void signIn(String role) {
        var jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "test"), Map.of("sub", "actor-1"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
    }
}
