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
 * Member invitations on the real security filter chain, the real permission function and real repositories (H2).
 * People: owner-a, admin-a, mgr-a, chk-a (active in event A), invitee (an account that has not been in event A), stranger.
 * The token handed out by the service is read from the notification it publishes, like the notification service does.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:memberinvite;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks",
        "member.invitation.max-per-event-per-day=4",
        "member.invitation.resend-min-seconds=0",
        "member.invitation.max-resends=2"
})
@AutoConfigureMockMvc
class MemberInvitationV2Test {

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
    void anInvitationIsPendingAndGivesNoPermissionUntilItIsAccepted() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.invitationExpiresAt").exists());
        StaffAssignment row = rowOf("u-fresh1");
        assertThat(row.getStatus()).isEqualTo(StaffAssignmentStatus.PENDING);
        assertThat(row.getInvitedBy()).isEqualTo("u-owner-a");
        // a pending invitation is not a membership: no role, no permission, not in my-events, no member-only call
        assertThat(permissionsOf("fresh1")).isZero();
        mvc.perform(as(get("/api/events/" + a + "/members"), "fresh1")).andExpect(status().isForbidden());
        assertThat(events.findMyEvents("u-fresh1")).isEmpty();
    }

    @Test
    void onlyTheHashOfTheTokenIsStoredAndNoPasswordIsPublished() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "CHECKIN_STAFF").andExpect(status().isOk());
        String token = lastToken();
        StaffAssignment row = rowOf("u-fresh1");
        assertThat(row.getInvitationTokenHash()).isEqualTo(sha256(token)).isNotEqualTo(token);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("notification.event.member.invitation"), payload.capture());
        assertThat(String.valueOf(payload.getValue())).doesNotContain("generatedPassword").doesNotContain("assword");
    }

    @Test
    void theInvitedAccountAcceptsOnceAndThenHasTheRole() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String token = lastToken();
        answer("preview", "fresh1", token).andExpect(status().isOk()).andExpect(jsonPath("$.eventTitle").value("A"))
                .andExpect(jsonPath("$.role").value("MANAGER")).andExpect(jsonPath("$.state").value("PENDING"));
        answer("accept", "fresh1", token).andExpect(status().isOk()).andExpect(jsonPath("$.eventId").value(a));
        assertThat(rowOf("u-fresh1").getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
        assertThat(rowOf("u-fresh1").getInvitationTokenHash()).isNull();
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "fresh1")).andExpect(jsonPath("$.role").value("MANAGER"));
        String assignmentId = rowOf("u-fresh1").getId();
        mvc.perform(as(get("/api/events/" + a + "/members"), "owner-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].userId".formatted(assignmentId))
                        .value(org.hamcrest.Matchers.contains("u-fresh1")));
        // the token is single-use
        answer("accept", "fresh1", token).andExpect(status().isNotFound()).andExpect(status().reason("INVITATION_NOT_FOUND"));
        answer("preview", "fresh1", token).andExpect(status().isNotFound());
    }

    @Test
    void anotherSignedInPersonCannotUseSomeoneElsesInvitation() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "ADMIN").andExpect(status().isOk());
        String token = lastToken();
        for (String who : List.of("stranger", "chk-a", "owner-a")) {
            answer("preview", who, token).andExpect(status().isNotFound()).andExpect(status().reason("INVITATION_NOT_FOUND"));
            answer("accept", who, token).andExpect(status().isNotFound());
            answer("decline", who, token).andExpect(status().isNotFound());
        }
        // a wrong token looks the same as somebody else's
        answer("accept", "fresh1", "not-the-token").andExpect(status().isNotFound()).andExpect(status().reason("INVITATION_NOT_FOUND"));
        assertThat(rowOf("u-fresh1").getStatus()).isEqualTo(StaffAssignmentStatus.PENDING);
        assertThat(permissionsOf("stranger")).isZero();
    }

    @Test
    void anExpiredInvitationCannotBeAcceptedAndIsListedAsExpiredUntilItIsSentAgain() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String oldToken = lastToken();
        StaffAssignment row = rowOf("u-fresh1");
        row.setInvitationExpiresAt(LocalDateTime.now().minusMinutes(1));
        assignments.save(row);
        answer("preview", "fresh1", oldToken).andExpect(status().isNotFound());
        answer("accept", "fresh1", oldToken).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/events/" + a + "/members"), "owner-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'fresh1@example.invalid')].status").value("EXPIRED"));
        // sent again: a new token, the old one stays dead
        clearInvocations(kafka);
        mvc.perform(as(post("/api/events/" + a + "/members/" + row.getId() + "/resend"), "owner-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        String newToken = lastToken();
        assertThat(newToken).isNotEqualTo(oldToken);
        answer("accept", "fresh1", oldToken).andExpect(status().isNotFound());
        answer("accept", "fresh1", newToken).andExpect(status().isOk());
    }

    @Test
    void aDeclinedInvitationCannotBeAcceptedButCanBeSentAgain() throws Exception {
        invite("admin-a", "fresh1@example.invalid", "CHECKIN_STAFF").andExpect(status().isOk());
        String token = lastToken();
        answer("decline", "fresh1", token).andExpect(status().isOk());
        assertThat(rowOf("u-fresh1").getStatus()).isEqualTo(StaffAssignmentStatus.DECLINED);
        answer("accept", "fresh1", token).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/events/" + a + "/members"), "owner-a"))
                .andExpect(jsonPath("$[?(@.email == 'fresh1@example.invalid')].status").value("DECLINED"));
        // invited again from the invite call: the same row is reused with a new token
        clearInvocations(kafka);
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(assignments.findByEventId(a).stream().filter(row -> "u-fresh1".equals(row.getUserId())).count()).isEqualTo(1);
        answer("accept", "fresh1", lastToken()).andExpect(status().isOk());
        assertThat(rowOf("u-fresh1").getRole()).isEqualTo(StaffRole.MANAGER);
    }

    @Test
    void cancellingAnInvitationKillsItsToken() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String token = lastToken();
        mvc.perform(as(delete("/api/events/" + a + "/members/" + rowOf("u-fresh1").getId()), "owner-a")).andExpect(status().isNoContent());
        answer("accept", "fresh1", token).andExpect(status().isNotFound());
        assertThat(assignments.findByEventIdAndUserId(a, "u-fresh1")).isEmpty();
    }

    @Test
    void aRemovedMemberHasNoPermissionAndMustAcceptAgainToComeBack() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        answer("accept", "fresh1", lastToken()).andExpect(status().isOk());
        String id = rowOf("u-fresh1").getId();
        mvc.perform(as(put("/api/events/" + a + "/members/" + id + "/status").param("newStatus", "REVOKED"), "owner-a")).andExpect(status().isOk());
        assertThat(permissionsOf("fresh1")).isZero();
        clearInvocations(kafka);
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(permissionsOf("fresh1")).isZero();
        answer("accept", "fresh1", lastToken()).andExpect(status().isOk());
        assertThat(permissionsOf("fresh1")).isEqualTo(1);
    }

    // --- who may invite, and what ----------------------------------------------------------------------------------------

    @Test
    void onlyOwnerAndAdminInviteAndNeverAnOwner() throws Exception {
        for (String who : List.of("mgr-a", "chk-a", "stranger")) {
            invite(who, "fresh1@example.invalid", "CHECKIN_STAFF").andExpect(status().isForbidden());
        }
        invite("admin-a", "fresh1@example.invalid", "OWNER").andExpect(status().isForbidden());
        invite("owner-a", "fresh1@example.invalid", "OWNER").andExpect(status().isUnprocessableEntity()).andExpect(status().reason("CANNOT_INVITE_OWNER"));
        assertThat(assignments.findByEventIdAndUserId(a, "u-fresh1")).isEmpty();
        invite("admin-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
    }

    @Test
    void invitingTheSamePersonTwiceOrAMemberIsRefused() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        invite("owner-a", "Fresh1@Example.Invalid", "MANAGER").andExpect(status().isConflict()).andExpect(status().reason("INVITATION_PENDING"));
        invite("owner-a", "mgr-a@example.invalid", "MANAGER").andExpect(status().isConflict()).andExpect(status().reason("ALREADY_MEMBER"));
    }

    @Test
    void theDailyLimitCountsInvitationsAndResendsFromTheDatabaseAndSaysWhenToRetry() throws Exception {
        for (int i = 1; i <= 3; i++) invite("owner-a", "fresh" + i + "@example.invalid", "CHECKIN_STAFF").andExpect(status().isOk());
        String resend = "/api/events/" + a + "/members/" + rowOf("u-fresh1").getId() + "/resend";
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isOk()); // 3 invitations + 1 re-send = the limit of 4
        invite("owner-a", "fresh5@example.invalid", "CHECKIN_STAFF").andExpect(status().isTooManyRequests())
                .andExpect(status().reason("INVITATION_RATE_LIMITED")).andExpect(header().exists("Retry-After"));
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(as(post(resend), "chk-a")).andExpect(status().isForbidden());
        assertThat(Long.parseLong(mvc.perform(as(post(resend), "owner-a")).andReturn().getResponse().getHeader("Retry-After")))
                .isBetween(1L, 24 * 3600L);
    }

    @Test
    void anInvitationCanBeSentAgainOnlyAFewTimes() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String resend = "/api/events/" + a + "/members/" + rowOf("u-fresh1").getId() + "/resend";
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isOk());
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isOk());
        mvc.perform(as(post(resend), "owner-a")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(rowOf("u-fresh1").getInvitationResendCount()).isEqualTo(2);
    }

    @Test
    void anExpiredInvitationIsInvitedAgainOnTheSameRow() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String oldToken = lastToken();
        StaffAssignment row = rowOf("u-fresh1");
        row.setInvitationExpiresAt(LocalDateTime.now().minusMinutes(1));
        assignments.save(row);
        clearInvocations(kafka);
        invite("owner-a", "fresh1@example.invalid", "ADMIN").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(assignments.findAllByEventIdAndUserId(a, "u-fresh1")).hasSize(1);
        assertThat(rowOf("u-fresh1").getId()).isEqualTo(row.getId());
        assertThat(rowOf("u-fresh1").getRole()).isEqualTo(StaffRole.ADMIN);
        answer("accept", "fresh1", oldToken).andExpect(status().isNotFound());
        answer("accept", "fresh1", lastToken()).andExpect(status().isOk());
    }

    @Test
    void aRemovedMemberAndADeclinedInvitationAreInvitedAgainOnTheSameRow() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        answer("accept", "fresh1", lastToken()).andExpect(status().isOk());
        String id = rowOf("u-fresh1").getId();
        mvc.perform(as(put("/api/events/" + a + "/members/" + id + "/status").param("newStatus", "REVOKED"), "owner-a")).andExpect(status().isOk());
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        assertThat(assignments.findAllByEventIdAndUserId(a, "u-fresh1")).hasSize(1);
        assertThat(rowOf("u-fresh1").getId()).isEqualTo(id);
    }

    @Test
    void ownershipCannotGoToSomeoneWhoIsNotAnActiveMember() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        invite("owner-a", "fresh2@example.invalid", "MANAGER").andExpect(status().isOk());
        answer("decline", "fresh2", lastToken()).andExpect(status().isOk());
        invite("owner-a", "fresh3@example.invalid", "MANAGER").andExpect(status().isOk());
        answer("accept", "fresh3", lastToken()).andExpect(status().isOk());
        mvc.perform(as(put("/api/events/" + a + "/members/" + rowOf("u-fresh3").getId() + "/status").param("newStatus", "REVOKED"), "owner-a"))
                .andExpect(status().isOk());
        // pending, declined, revoked: none can become the owner, and neither can a declined row be switched on
        for (String who : List.of("u-fresh1", "u-fresh2", "u-fresh3")) {
            mvc.perform(as(post("/api/events/" + a + "/members/" + rowOf(who).getId() + "/transfer-owner"), "owner-a")).andExpect(status().isBadRequest());
        }
        mvc.perform(as(put("/api/events/" + a + "/members/" + rowOf("u-fresh2").getId() + "/status").param("newStatus", "ACTIVE"), "owner-a"))
                .andExpect(status().isBadRequest());
        assertThat(rowOf("u-owner-a").getRole()).isEqualTo(StaffRole.OWNER);
        for (String who : List.of("fresh1", "fresh2")) assertThat(permissionsOf(who)).isZero();
        assertThat(events.findMyEvents("u-fresh1")).isEmpty();
        assertThat(events.findMyEvents("u-fresh2")).isEmpty();
    }

    @Test
    void duplicateRowsFromBeforeTheUniqueIndexDoNotBreakAnInvitation() throws Exception {
        Event event = events.findById(a).orElseThrow();
        member(event, "u-dup", StaffRole.MANAGER);
        member(event, "u-dup", StaffRole.MANAGER);
        invite("owner-a", "dup@example.invalid", "MANAGER").andExpect(status().isConflict()).andExpect(status().reason("ALREADY_MEMBER"));
    }

    @Test
    void anInvitationIsNotAMemberForOwnershipOrTheStatusSwitch() throws Exception {
        invite("owner-a", "fresh1@example.invalid", "MANAGER").andExpect(status().isOk());
        String id = rowOf("u-fresh1").getId();
        mvc.perform(as(put("/api/events/" + a + "/members/" + id + "/status").param("newStatus", "ACTIVE"), "owner-a"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/events/" + a + "/members/" + id + "/status").param("newStatus", "PENDING"), "owner-a"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/events/" + a + "/members/" + id + "/transfer-owner"), "owner-a")).andExpect(status().isBadRequest());
        assertThat(rowOf("u-fresh1").getStatus()).isEqualTo(StaffAssignmentStatus.PENDING);
        assertThat(rowOf("u-owner-a").getRole()).isEqualTo(StaffRole.OWNER);
        // the answering endpoints need a signed-in caller
        mvc.perform(post("/api/events/member-invitations/accept").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
