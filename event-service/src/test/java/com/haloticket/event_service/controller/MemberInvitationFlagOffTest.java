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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Member invitations with the v2 flag off (the default): the old rule that an invited person is a member at once is kept,
 * the invitation endpoints answer as if they did not exist, and no flow carries a password.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:memberinviteoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        "authz.event-scope.members.enabled=true"
})
@AutoConfigureMockMvc
class MemberInvitationFlagOffTest {

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
        when(users.getOrganizerEligibility(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0, String.class);
            return new OrganizerEligibilityResponse(true, "u-" + subject.replaceFirst("^kc-", ""), null, null);
        });
        when(internalUsers.inviteUser(any())).thenAnswer(call -> {
            String email = call.getArgument(0, com.haloticket.event_service.dto.request.EventMemberInviteRequest.class).getEmail();
            String who = email.substring(0, email.indexOf('@'));
            UserInviteResponse response = new UserInviteResponse();
            response.setUserId("u-" + who);
            response.setEmail(email);
            response.setName(who);
            response.setNewUser(true);
            return response;
        });
        a = events.save(Event.builder().title("A").build()).getId();
        assignments.save(StaffAssignment.builder().event(events.findById(a).orElseThrow()).userId("u-owner-a")
                .role(StaffRole.OWNER).status(StaffAssignmentStatus.ACTIVE).build());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        Jwt jwt = Jwt.withTokenValue("kc-" + who).header("alg", "none").subject("kc-" + who)
                .claim("roles", List.of("ROLE_USER")).build();
        return request.with(authentication(converter.convert(jwt))).header("Authorization", "kc-" + who);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anInvitedPersonIsAMemberAtOnceAndNoPasswordIsPublished() throws Exception {
        mvc.perform(as(post("/api/events/" + a + "/members"), "owner-a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"fresh1@example.invalid\",\"role\":\"CHECKIN_STAFF\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        StaffAssignment row = assignments.findByEventIdAndUserId(a, "u-fresh1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
        assertThat(row.getInvitationTokenHash()).isNull();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("notification.event.member.invitation"), payload.capture());
        Map<String, Object> sent = (Map<String, Object>) payload.getValue();
        assertThat(sent).doesNotContainKeys("generatedPassword", "invitationToken");
        assertThat(sent.get("isNewUser")).isEqualTo(true);
    }

    @Test
    void theInvitationEndpointsAnswerAsIfTheyDidNotExist() throws Exception {
        for (String path : List.of("preview", "accept", "decline")) {
            mvc.perform(as(post("/api/events/member-invitations/" + path), "owner-a").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"anything\"}")).andExpect(status().isNotFound());
        }
    }
}
