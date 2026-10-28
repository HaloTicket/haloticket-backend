package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.client.UserInternalServiceClient;
import com.haloticket.event_service.client.UserInviteResponse;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.config.DataInitializer;
import com.haloticket.event_service.config.DatabaseSchemaInitializer;
import com.haloticket.event_service.config.JwtRoleConverter;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The minimum gap between two re-sends of one invitation, with v2 on. Member invitations with v2 on, on the real security filter chain, the real permission function and real repositories (H2).
 * People: owner-a, admin-a, mgr-a, chk-a (active in event A), invitee (an account that has not been in event A), stranger.
 * The token handed out by the service is read from the notification it publishes, like the notification service does.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:memberinvitegap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks",
        "authz.event-scope.enabled=true",
        "authz.event-scope.legacy-fallback=false",
        "authz.event-scope.permission-function.enabled=true",
        "authz.event-scope.members.enabled=true",
        "member.invitation.v2.enabled=true",
        "member.invitation.max-per-event-per-day=50",
        "member.invitation.resend-min-seconds=300",
        "member.invitation.max-resends=2"
})
@AutoConfigureMockMvc
class MemberInvitationResendGapTest {

    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired StaffAssignmentRepository assignments;
    @Autowired JwtRoleConverter converter;
    @Autowired CallerIds callerIds;

    @MockitoBean DataInitializer dataInitializer;
    @MockitoBean DatabaseSchemaInitializer schemaInitializer;
    @MockitoBean UserServiceClient users;
    @MockitoBean UserInternalServiceClient internalUsers;
    @MockitoBean KafkaTemplate<String, Object> kafka;

    String a;

    @BeforeEach
    void data() {
        assignments.deleteAll();
        events.deleteAll();
        callerIds.clear();
        clearInvocations(kafka);
        when(users.getOrganizerEligibility(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0, String.class);
            return new OrganizerEligibilityResponse(true, "u-" + subject.replaceFirst("^kc-", ""), null, null);
        });
        // user-service: an e-mail local part "who" is the account "u-who"; it reports the account as new for "fresh" addresses
        when(internalUsers.inviteUser(any())).thenAnswer(call -> {
            String email = call.getArgument(0, com.haloticket.event_service.dto.request.EventMemberInviteRequest.class).getEmail();
            String who = email.substring(0, email.indexOf('@'));
            UserInviteResponse response = new UserInviteResponse();
            response.setUserId("u-" + who);
            response.setEmail(email);
            response.setName(who);
            response.setNewUser(who.startsWith("fresh"));
            return response;
        });
        a = events.save(Event.builder().title("A").build()).getId();
        Event eventA = events.findById(a).orElseThrow();
        member(eventA, "u-owner-a", StaffRole.OWNER);
        member(eventA, "u-admin-a", StaffRole.ADMIN);
        member(eventA, "u-mgr-a", StaffRole.MANAGER);
        member(eventA, "u-chk-a", StaffRole.CHECKIN_STAFF);
    }

    private String member(Event event, String userId, StaffRole role) {
        return assignments.save(StaffAssignment.builder().event(event).userId(userId).role(role)
                .status(StaffAssignmentStatus.ACTIVE).build()).getId();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        Jwt jwt = Jwt.withTokenValue("kc-" + who).header("alg", "none").subject("kc-" + who)
                .claim("roles", List.of("ROLE_USER")).build();
        return request.with(authentication(converter.convert(jwt))).header("Authorization", "kc-" + who);
    }

    private ResultActions invite(String who, String email, String role) throws Exception {
        return mvc.perform(as(post("/api/events/" + a + "/members"), who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"));
    }

    private ResultActions answer(String path, String who, String token) throws Exception {
        return mvc.perform(as(post("/api/events/member-invitations/" + path), who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    /** The token of the last invitation notification. */
    @SuppressWarnings("unchecked")
    private String lastToken() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka, org.mockito.Mockito.atLeastOnce()).send(eq("notification.event.member.invitation"), payload.capture());
        List<Object> all = payload.getAllValues();
        return String.valueOf(((Map<String, Object>) all.get(all.size() - 1)).get("invitationToken"));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private StaffAssignment rowOf(String userId) {
        return assignments.findByEventIdAndUserId(a, userId).orElseThrow();
    }

    private int permissionsOf(String who) throws Exception {
        String body = mvc.perform(as(get("/api/events/" + a + "/members/my-access"), who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.contains("\"permissions\":[]") ? 0 : 1;
    }

    // --- the invitation --------------------------------------------------------------------------------------------------

    @Test
    void aSecondResendRightAfterTheFirstIsRefusedWithRetryAfter() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String resend = "/api/events/" + a + "/members/" + rowOf("u-fresh1").getId() + "/resend";
        // the invitation itself was just sent, so a re-send has to wait for the gap
        String wait = mvc.perform(as(post(resend), "owner-a")).andExpect(status().isTooManyRequests())
                .andExpect(status().reason("INVITATION_RATE_LIMITED")).andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(wait)).isBetween(1L, 301L);
        // once the gap has passed it goes out, and then has to wait again
        StaffAssignment row = rowOf("u-fresh1");
        row.setInvitedAt(LocalDateTime.now().minusSeconds(301));
        assignments.save(row);
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isOk());
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}
