package com.haloticket.notification_service.controller;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
import com.haloticket.notification_service.authz.NotificationScopeProperties;
import com.haloticket.notification_service.client.AnnouncementScanClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient.EventScope;
import com.haloticket.notification_service.client.UserServiceClient;
import com.haloticket.notification_service.dto.response.AnnouncementScan;
import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.exception.GlobalExceptionHandler;
import com.haloticket.notification_service.repository.AnnouncementStore;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.service.OrganizerAnnouncementService;
import com.haloticket.notification_service.service.OrganizerRecipients;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import feign.FeignException;
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

class OrganizerAnnouncementControllerTest {
    private static final String EVENT = "/api/notifications/events/event/announcements";
    private static final String MODERATION = "/api/notifications/admin/moderation";
    private static final String SUBMISSION = """
            {"requestId":"1837f618-28a8-4cb4-aa49-fb1d15f68030","title":"Title","message":"Message",
             "category":"EVENT_UPDATE","eventTitle":"Spoofed event","audience":"ALL"}
            """;
    private final AnnouncementStore store = mock(AnnouncementStore.class);
    private final EventAnnouncementScopeClient events = mock(EventAnnouncementScopeClient.class);
    private final AnnouncementScanClient scanner = mock(AnnouncementScanClient.class);
    private final OrganizerRecipients recipients = mock(OrganizerRecipients.class);
    private final NotificationScopeGuard scopes = new NotificationScopeGuard(new NotificationScopeProperties(),
            mock(UserServiceClient.class), mock(NotificationRepository.class));
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new OrganizerAnnouncementController(new OrganizerAnnouncementService(store, events, scanner, recipients), scopes))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizerRequiresBearerAndEventScope() throws Exception {
        var forbidden = mock(FeignException.class);
        when(forbidden.status()).thenReturn(403);
        when(events.scope("event", "Bearer wrong-event")).thenThrow(forbidden);
        mvc.perform(get(EVENT)).andExpect(status().isUnauthorized());
        mvc.perform(get(EVENT).header("Authorization", "Bearer wrong-event")).andExpect(status().isForbidden());
        verifyNoInteractions(store, scanner);
    }

    @Test
    void organizerSubmissionUsesTrustedTitleAndFixedBuyerAudience() throws Exception {
        when(events.scope("event", "Bearer manager")).thenReturn(new EventScope("event", "Trusted event", "manager", "MANAGER"));
        when(recipients.count("event", "manager", AnnouncementTarget.CUSTOMER_ALL)).thenReturn(4L);
        when(store.insert(any())).thenAnswer(call -> call.getArgument(0, AdminAnnouncement.class));
        when(scanner.scan("Title", "Message")).thenReturn(AnnouncementScan.unavailable());
        mvc.perform(post(EVENT).header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMISSION))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.eventTitle").value("Trusted event"))
                .andExpect(jsonPath("$.audience").value("EVENT_BUYERS"))
                .andExpect(jsonPath("$.target.group").value("CUSTOMER"))
                .andExpect(jsonPath("$.previewRecipientCount").value(4))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.scan.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.scan.score").doesNotExist());
    }

    @Test
    void previewIsScopedAndReturnsOnlyTheCount() throws Exception {
        when(events.scope("event", "Bearer manager"))
                .thenReturn(new EventScope("event", "Trusted event", "manager", "MANAGER"));
        mvc.perform(post(EVENT + "/recipient-count").header("Authorization", "Bearer manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":{\"group\":\"STAFF\",\"scope\":\"ALL\"}}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"recipientCount\":0}", true));
    }

    @Test
    void newTargetRequiresMatchingCountAndStaffBypassesReview() throws Exception {
        when(events.scope("event", "Bearer manager"))
                .thenReturn(new EventScope("event", "Trusted event", "manager", "MANAGER"));
        when(recipients.count(eq("event"), eq("manager"), any())).thenReturn(2L);
        when(store.insert(any())).thenAnswer(call -> call.getArgument(0, AdminAnnouncement.class));
        var staff = SUBMISSION.strip().replace("EVENT_UPDATE", "INTERNAL").replace("}", """
                ,"target":{"group":"STAFF","scope":"ALL"}}
                """);
        mvc.perform(post(EVENT).header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                .content(staff)).andExpect(status().isBadRequest());
        var confirmed = staff.replace("\"target\"", "\"expectedRecipientCount\":1,\"target\"");
        mvc.perform(post(EVENT).header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                        .content(confirmed)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_COUNT_CHANGED"));
        mvc.perform(post(EVENT).header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                        .content(confirmed.replace("Count\":1", "Count\":2"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.target.group").value("STAFF"))
                .andExpect(jsonPath("$.previewRecipientCount").value(2));
        verifyNoInteractions(scanner);
    }

    @Test
    void missingAnnouncementRouteReturns404() throws Exception {
        mvc.perform(get(EVENT + "/missing/unknown")).andExpect(status().isNotFound());
        mvc.perform(get(EVENT + "/missing")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void previewRejectsCrossGroupScopesAndPropagatesTargetNotFound() throws Exception {
        when(events.scope("event", "Bearer manager"))
                .thenReturn(new EventScope("event", "Trusted event", "manager", "MANAGER"));
        var notFound = mock(FeignException.class);
        when(notFound.status()).thenReturn(404);
        when(recipients.count(eq("event"), eq("manager"), any())).thenThrow(notFound);
        mvc.perform(post(EVENT + "/recipient-count").header("Authorization", "Bearer manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":{\"group\":\"STAFF\",\"scope\":\"TICKET_TYPE\",\"ticketTypeId\":\"type\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(EVENT + "/recipient-count").header("Authorization", "Bearer manager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":{\"group\":\"CUSTOMER\",\"scope\":\"TICKET_TYPE\",\"ticketTypeId\":\"type\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"));
    }

    @Test
    void resubmissionRejectsStaleCountAndStaffPromotion() throws Exception {
        when(events.scope("event", "Bearer manager"))
                .thenReturn(new EventScope("event", "Trusted event", "manager", "MANAGER"));
        when(store.findById("job")).thenReturn(AdminAnnouncement.builder().organizerEventId("event")
                .status(AdminAnnouncement.Status.CHANGES_REQUESTED).revision(2).build());
        when(recipients.count(eq("event"), eq("manager"), any())).thenReturn(2L);
        var update = """
                {"revision":2,"title":"Title","message":"Message","category":"INTERNAL","expectedRecipientCount":1,
                 "target":{"group":"STAFF","scope":"CHECKIN_STAFF"}}
                """;
        mvc.perform(put(EVENT + "/job").header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                        .content(update)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_COUNT_CHANGED"));
        mvc.perform(put(EVENT + "/job").header("Authorization", "Bearer manager").contentType(MediaType.APPLICATION_JSON)
                        .content(update.replace("INTERNAL", "PROMOTION"))).andExpect(status().isBadRequest());
        verifyNoInteractions(scanner);
    }

    @Test
    void moderationRequiresAdminAndSuppliesDefaultPagination() throws Exception {
        signIn("ROLE_CUSTOMER");
        mvc.perform(get(MODERATION)).andExpect(status().isForbidden());
        mvc.perform(patch(MODERATION + "/job").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1,\"action\":\"APPROVE\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(store);
        signIn("ROLE_ADMIN");
        when(store.organizerPage(null, "expo", 0, 20)).thenReturn(new AnnouncementStore.OrganizerPage(List.of(), 0));
        mvc.perform(get(MODERATION).param("q", " expo ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.total").value(0));
    }

    private static void signIn(String role) {
        var jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "test"), Map.of("sub", "actor"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
    }
}
