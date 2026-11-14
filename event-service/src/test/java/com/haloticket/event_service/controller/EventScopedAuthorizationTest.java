package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.config.DataInitializer;
import com.haloticket.event_service.config.DatabaseSchemaInitializer;
import com.haloticket.event_service.config.JwtRoleConverter;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventAnalyticsResponse;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventAnalyticsService;
import com.haloticket.event_service.service.EventService;
import com.haloticket.event_service.service.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The event-scoped authorization matrix, on the real security filter chain, the real permission function and real
 * repositories (H2). Only the other services and the business services behind the controllers are replaced.
 *
 * People (Keycloak subject "kc-x" is the token's subject; user-service says their id is "u-x", a different value):
 * owner-a, admin-a, mgr-a, chk-a (active in event A), rev-a (REVOKED in A), owner-b (owner of B), cross (check-in in A and
 * owner of B), stranger (in no event), root (ROLE_ADMIN of the platform), and nobody (anonymous).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eventscope;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class EventScopedAuthorizationTest {

    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired StaffAssignmentRepository assignments;
    @Autowired JwtRoleConverter converter;
    @Autowired CallerIds callerIds;
    @Autowired EventService eventService;
    @Autowired EventOrganizerRepository organizers;

    // the startup seeders target PostgreSQL and the dev data; the test builds its own rows
    @MockitoBean DataInitializer dataInitializer;
    @MockitoBean DatabaseSchemaInitializer schemaInitializer;
    @MockitoBean UserServiceClient users;
    @MockitoBean EventAnalyticsService analytics;
    @MockitoBean VoucherService vouchers;

    String a;
    String b;
    String assignmentOfB;
    String assignmentOfStrangerInA;

    @BeforeEach
    void data() {
        assignments.deleteAll();
        events.deleteAll();
        organizers.deleteAll();
        callerIds.clear();
        // user-service maps the token's subject ("kc-x") to a different id ("u-x"); the token string carries the subject
        when(users.getOrganizerEligibility(anyString())).thenAnswer(call -> {
            String subject = call.getArgument(0, String.class);
            return new OrganizerEligibilityResponse(true, "u-" + subject.replaceFirst("^kc-", ""), null, null);
        });
        a = events.save(Event.builder().title("A").build()).getId();
        b = events.save(Event.builder().title("B").build()).getId();
        Event eventA = events.findById(a).orElseThrow();
        Event eventB = events.findById(b).orElseThrow();
        member(eventA, "u-owner-a", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE);
        member(eventA, "u-admin-a", StaffRole.ADMIN, StaffAssignmentStatus.ACTIVE);
        member(eventA, "u-mgr-a", StaffRole.MANAGER, StaffAssignmentStatus.ACTIVE);
        member(eventA, "u-chk-a", StaffRole.CHECKIN_STAFF, StaffAssignmentStatus.ACTIVE);
        member(eventA, "u-rev-a", StaffRole.MANAGER, StaffAssignmentStatus.REVOKED);
        member(eventA, "u-cross", StaffRole.CHECKIN_STAFF, StaffAssignmentStatus.ACTIVE);
        // a Keycloak-id-shaped value stored as a user id: whoever has that SUBJECT must not inherit it
        assignmentOfStrangerInA = member(eventA, "kc-victim", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE);
        member(eventB, "u-owner-b", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE);
        assignmentOfB = member(eventB, "u-cross", StaffRole.OWNER, StaffAssignmentStatus.ACTIVE);
        when(vouchers.eventIdOf("voucher-a")).thenReturn(a);
        when(vouchers.eventIdOf("voucher-b")).thenReturn(b);
        when(vouchers.getVouchersByEventId(anyString())).thenAnswer(call -> List.of(VoucherResponse.builder().id("v").code("SECRET10").build()));
        when(analytics.getEventAnalytics(anyString(), anyString())).thenReturn(null);
    }

    private String member(Event event, String userId, StaffRole role, StaffAssignmentStatus status) {
        return assignments.save(StaffAssignment.builder().event(event).userId(userId).role(role).status(status).build()).getId();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        if (who == null) return request;
        Jwt jwt = Jwt.withTokenValue("kc-" + who).header("alg", "none").subject("kc-" + who)
                .claim("roles", who.equals("root") ? List.of("ROLE_USER", "ROLE_ADMIN") : List.of("ROLE_USER")).build();
        return request.with(authentication(converter.convert(jwt))).header("Authorization", "kc-" + who);
    }

    private int codeOf(MockHttpServletRequestBuilder request, String who) throws Exception {
        try {
            return mvc.perform(as(request, who)).andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException businessFailure) {
            return 500; // authorization let the call through and the business code then refused the empty body
        }
    }

    // --- what the function says ------------------------------------------------------------------------------------------

    @Test
    void myAccessListsTheRoleAndThePermissionsOfEachMember() throws Exception {
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "owner-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER")).andExpect(jsonPath("$.permissions[?(@ == 'FINANCE')]").exists())
                .andExpect(jsonPath("$.userId").value("u-owner-a")); // the user-service id, not the subject
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "admin-a")).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[?(@ == 'FINANCE')]").doesNotExist())
                .andExpect(jsonPath("$.permissions[?(@ == 'EVENT_EDIT')]").exists());
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "mgr-a")).andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions[?(@ == 'ORDERS_VIEW')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'EVENT_EDIT')]").doesNotExist());
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "chk-a")).andExpect(jsonPath("$.role").value("CHECKIN_STAFF"))
                .andExpect(jsonPath("$.permissions.length()").value(2));
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "root")).andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.permissions[?(@ == 'FINANCE')]").exists());
    }

    @Test
    void aRemovedMemberAndAStrangerHaveNoPermissionsAndNoRole() throws Exception {
        for (String who : List.of("rev-a", "stranger", "owner-b")) {
            mvc.perform(as(get("/api/events/" + a + "/members/my-access"), who)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.length()").value(0)).andExpect(jsonPath("$.role").doesNotExist());
            assertThat(codeOf(get("/api/events/" + a + "/members/my-role"), who)).as("my-role " + who).isEqualTo(403);
        }
        assertThat(codeOf(get("/api/events/" + a + "/members/my-role"), "mgr-a")).isEqualTo(200);
    }

    @Test
    void theSubjectIsNeverComparedWithTheAssignmentTable() throws Exception {
        // "kc-victim" is stored in event A as an OWNER user id. A caller whose token SUBJECT is "kc-victim" is the person
        // user-service calls "u-victim": they own nothing, although their subject equals a stored value.
        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "victim")).andExpect(jsonPath("$.userId").value("u-victim"))
                .andExpect(jsonPath("$.permissions.length()").value(0));
    }

    @Test
    void unknownEventIs404AndAnonymousIs401() throws Exception {
        assertThat(codeOf(get("/api/events/nope/members/my-access"), "owner-a")).isEqualTo(404);
        assertThat(codeOf(get("/api/events/" + a + "/members/my-access"), null)).isIn(400, 401);
    }

    // --- one permission at a time, for event A ---------------------------------------------------------------------------

    private void expectGrantedOnlyTo(String path, List<String> allowed, MockHttpServletRequestBuilder... unused) throws Exception {
        for (String who : List.of("owner-a", "admin-a", "mgr-a", "chk-a", "rev-a", "stranger", "owner-b", "cross", "root")) {
            // "cross" is a check-in member of A and the owner of B
            int code = codeOf(get(path), who);
            if (allowed.contains(who)) assertThat(code).as(path + " for " + who).isEqualTo(200);
            else assertThat(code).as(path + " for " + who).isEqualTo(403);
        }
        assertThat(codeOf(get(path), null)).as(path + " anonymous").isIn(400, 401); // 400: no Authorization header; the gateway answers 401 first
    }

    @Test
    void membersCanBeListedByOwnerAdminManager() throws Exception {
        expectGrantedOnlyTo("/api/events/" + a + "/members", List.of("owner-a", "admin-a", "mgr-a", "root"));
    }

    @Test
    void transferSettingsCanBeReadByOwnerAdminManager() throws Exception {
        expectGrantedOnlyTo("/api/events/" + a + "/transfer-settings", List.of("owner-a", "admin-a", "mgr-a", "root"));
    }

    @Test
    void analyticsAreForOwnerAdminManager() throws Exception {
        expectGrantedOnlyTo("/api/events/" + a + "/analytics", List.of("owner-a", "admin-a", "mgr-a", "root"));
    }

    @Test
    void theSameCallsOnTheOtherEventAreRefusedToTheMembersOfA() throws Exception {
        for (String who : List.of("owner-a", "admin-a", "mgr-a", "chk-a")) {
            assertThat(codeOf(get("/api/events/" + b + "/members"), who)).as("members of B for " + who).isEqualTo(403);
            assertThat(codeOf(get("/api/events/" + b + "/analytics"), who)).as("analytics of B for " + who).isEqualTo(403);
            assertThat(codeOf(get("/api/events/" + b + "/transfer-settings"), who)).as("settings of B for " + who).isEqualTo(403);
        }
        // the person who is both: check-in staff of A (no analytics) and owner of B (everything)
        assertThat(codeOf(get("/api/events/" + a + "/analytics"), "cross")).isEqualTo(403);
        assertThat(codeOf(get("/api/events/" + b + "/analytics"), "cross")).isEqualTo(200);
    }

    // --- vouchers -----------------------------------------------------------------------------------------------------------

    @Test
    void vouchersAreManagedByOwnerAndAdminOfTheEventOnly() throws Exception {
        String body = "{\"title\":\"t\"}";
        for (String who : List.of("owner-a", "admin-a")) {
            assertThat(codeOf(post("/api/events/" + a + "/vouchers").contentType(MediaType.APPLICATION_JSON).content(body), who))
                    .as("create by " + who).isEqualTo(201);
        }
        for (String who : List.of("mgr-a", "chk-a", "rev-a", "stranger", "owner-b")) {
            assertThat(codeOf(post("/api/events/" + a + "/vouchers").contentType(MediaType.APPLICATION_JSON).content(body), who))
                    .as("create by " + who).isEqualTo(403);
        }
        assertThat(codeOf(post("/api/events/" + a + "/vouchers").contentType(MediaType.APPLICATION_JSON).content(body), null)).isEqualTo(401);
    }

    @Test
    void aVoucherOfAnotherEventCannotBeChangedWithAnIdFromYourOwn() throws Exception {
        String body = "{}";
        // owner-a may change voucher-a, not voucher-b; owner-b the other way round
        assertThat(codeOf(put("/api/events/vouchers/voucher-a").contentType(MediaType.APPLICATION_JSON).content(body), "owner-a")).isEqualTo(200);
        assertThat(codeOf(put("/api/events/vouchers/voucher-b").contentType(MediaType.APPLICATION_JSON).content(body), "owner-a")).isEqualTo(403);
        assertThat(codeOf(delete("/api/events/vouchers/voucher-b"), "owner-a")).isEqualTo(403);
        assertThat(codeOf(delete("/api/events/vouchers/voucher-b"), "owner-b")).isEqualTo(204);
    }

    @Test
    void voucherCodesAreShownOnlyToThoseWhoRunTheVouchers() throws Exception {
        mvc.perform(as(get("/api/events/" + a + "/vouchers"), "owner-a")).andExpect(jsonPath("$[0].code").value("SECRET10"));
        for (String who : List.of("mgr-a", "stranger", "owner-b", "rev-a")) {
            mvc.perform(as(get("/api/events/" + a + "/vouchers"), who)).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").doesNotExist());
        }
        mvc.perform(get("/api/events/" + a + "/vouchers")).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").doesNotExist());
    }

    // --- members ------------------------------------------------------------------------------------------------------------

    @Test
    void anAssignmentOfAnotherEventIsNotFoundThroughThisEventsPath() throws Exception {
        // owner-a owns A; assignmentOfB belongs to B. Changing, revoking or removing it through /A/ must not work.
        assertThat(codeOf(put("/api/events/" + a + "/members/" + assignmentOfB + "/role").param("newRole", "MANAGER"), "owner-a")).isEqualTo(404);
        assertThat(codeOf(put("/api/events/" + a + "/members/" + assignmentOfB + "/status").param("newStatus", "REVOKED"), "owner-a")).isEqualTo(404);
        assertThat(codeOf(delete("/api/events/" + a + "/members/" + assignmentOfB), "owner-a")).isEqualTo(404);
        assertThat(assignments.findById(assignmentOfB)).isPresent();
        assertThat(assignments.findById(assignmentOfB).orElseThrow().getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
    }

    @Test
    void managersAndCheckInStaffCannotManageMembers() throws Exception {
        for (String who : List.of("mgr-a", "chk-a", "rev-a", "stranger", "owner-b")) {
            assertThat(codeOf(put("/api/events/" + a + "/members/" + assignmentOfStrangerInA + "/role").param("newRole", "MANAGER"), who))
                    .as("role change by " + who).isEqualTo(403);
        }
    }

    @Test
    void anInvalidStatusIs400NotAServerError() throws Exception {
        String own = assignments.findByEventId(a).stream().filter(m -> m.getUserId().equals("u-mgr-a")).findFirst().orElseThrow().getId();
        assertThat(codeOf(put("/api/events/" + a + "/members/" + own + "/status").param("newStatus", "BANANA"), "owner-a")).isEqualTo(400);
        assertThat(codeOf(put("/api/events/" + a + "/members/" + own + "/status").param("newStatus", "revoked"), "owner-a")).isEqualTo(200);
    }

    @Test
    void anAdminOfTheEventCannotTouchTheOwner() throws Exception {
        String owner = assignments.findByEventId(a).stream().filter(m -> m.getUserId().equals("u-owner-a")).findFirst().orElseThrow().getId();
        assertThat(codeOf(put("/api/events/" + a + "/members/" + owner + "/role").param("newRole", "MANAGER"), "admin-a")).isEqualTo(403);
        assertThat(codeOf(delete("/api/events/" + a + "/members/" + owner), "admin-a")).isEqualTo(403);
    }

    // --- my events -----------------------------------------------------------------------------------------------------------

    @Test
    void myEventsListsOnlyEventsWhereTheMembershipIsActive() throws Exception {
        assertThat(myEvents("chk-a")).containsExactly(a);
        assertThat(myEvents("rev-a")).isEmpty();
        assertThat(myEvents("stranger")).isEmpty();
        assertThat(myEvents("cross")).containsExactlyInAnyOrder(a, b);
        assertThat(myEvents("owner-b")).containsExactly(b);
    }

    private List<String> myEvents(String who) throws Exception {
        String json = mvc.perform(as(get("/api/events/my-events"), who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = new java.util.ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).forEach(event -> ids.add(event.get("id").asText()));
        return ids;
    }

    // --- platform configuration -------------------------------------------------------------------------------------------------

    @Test
    void platformConfigurationCanBeChangedByThePlatformAdminOnly() throws Exception {
        List<MockHttpServletRequestBuilder> writes = List.of(
                put("/api/events/admin/config/homepage/banners").contentType(MediaType.APPLICATION_JSON).content("[]"),
                put("/api/events/admin/config/homepage/sections").contentType(MediaType.APPLICATION_JSON).content("[]"),
                post("/api/events/categories").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/api/events/categories/order").contentType(MediaType.APPLICATION_JSON).content("[]"),
                put("/api/events/categories/c1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/events/categories/c1"),
                post("/api/events/venues").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/api/events/venues/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/events/venues/1"));
        for (MockHttpServletRequestBuilder write : writes) {
            for (String who : List.of("owner-a", "admin-a", "stranger")) {
                assertThat(codeOf(write, who)).as(who + " " + write).isEqualTo(403);
            }
            assertThat(codeOf(write, "root")).as("root " + write).isNotIn(401, 403);
        }
        assertThat(codeOf(get("/api/events/venues"), null)).as("reading stays public").isEqualTo(200);
    }

    @Test
    void whenUserServiceIsDownTheAnswerIs503NotANoAccess() throws Exception {
        when(users.getOrganizerEligibility(anyString())).thenThrow(new RuntimeException("connection refused"));
        callerIds.clear();
        assertThat(codeOf(get("/api/events/" + a + "/members/my-access"), "owner-a")).isEqualTo(503);
        assertThat(codeOf(get("/api/events/" + a + "/analytics"), "owner-a")).isEqualTo(503);
    }

    // --- who owns an event comes from staff_assignments only ---------------------------------------------------------------

    @Test
    void creatingAnEventGivesTheCreatorExactlyOneActiveOwnerAssignment() throws Exception {
        EventCreateRequest request = new EventCreateRequest();
        request.setTitle("Brand new");
        request.setOrganizerName("New Org");
        request.setStatus("DRAFT");
        String id = eventService.createEvent(request, "kc-newbie").getId();

        List<StaffAssignment> rows = assignments.findByEventId(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isEqualTo("u-newbie"); // the user-service id that the permission function compares
        assertThat(rows.get(0).getRole()).isEqualTo(StaffRole.OWNER);
        assertThat(rows.get(0).getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
        mvc.perform(as(get("/api/events/" + id + "/members/my-access"), "newbie")).andExpect(jsonPath("$.role").value("OWNER"));
        assertThat(myEvents("newbie")).containsExactly(id);
    }

    @Test
    void aPersonWhoIsOnlyInTheOrganizerProfileHasNoPermissionAndNoEvents() throws Exception {
        com.haloticket.event_service.entity.EventOrganizer profile = organizers.save(
                com.haloticket.event_service.entity.EventOrganizer.builder().userId("u-profile").name("Profile only").build());
        Event event = events.findById(a).orElseThrow();
        event.setOrganizers(new java.util.ArrayList<>(List.of(profile)));
        events.save(event);

        mvc.perform(as(get("/api/events/" + a + "/members/my-access"), "profile")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(0)).andExpect(jsonPath("$.role").doesNotExist());
        assertThat(codeOf(get("/api/events/" + a + "/members"), "profile")).isEqualTo(403);
        assertThat(codeOf(get("/api/events/" + a + "/analytics"), "profile")).isEqualTo(403);
        assertThat(myEvents("profile")).isEmpty();
        // and the members list shows no invented owner row for the profile
        mvc.perform(as(get("/api/events/" + a + "/members"), "owner-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(assignments.findByEventId(a).size()));
    }

    @Test
    void aPlatformAdminPassesOnAnEventThatHasNoAssignmentAtAll() throws Exception {
        String lonely = events.save(Event.builder().title("No owner row").build()).getId();
        mvc.perform(as(get("/api/events/" + lonely + "/members/my-access"), "root")).andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(true)).andExpect(jsonPath("$.permissions[?(@ == 'EVENT_EDIT')]").exists());
        assertThat(codeOf(get("/api/events/" + lonely + "/members"), "root")).isEqualTo(200);
        assertThat(codeOf(get("/api/events/" + lonely + "/analytics"), "root")).isEqualTo(200);
        assertThat(codeOf(get("/api/events/" + lonely + "/members"), "stranger")).isEqualTo(403);
    }
}
