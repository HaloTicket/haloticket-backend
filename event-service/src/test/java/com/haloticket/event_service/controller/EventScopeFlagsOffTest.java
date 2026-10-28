package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.CallerIds;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.config.DataInitializer;
import com.haloticket.event_service.config.DatabaseSchemaInitializer;
import com.haloticket.event_service.config.JwtRoleConverter;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventAnalyticsService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With every authz.event-scope switch at its default (off) the endpoints behave as before; the only change is the ACTIVE
 * filter, which is a fix and has no switch.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eventscopeoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
class EventScopeFlagsOffTest {

    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired StaffAssignmentRepository assignments;
    @Autowired JwtRoleConverter converter;
    @Autowired CallerIds callerIds;
    @MockitoBean DataInitializer dataInitializer;
    @MockitoBean DatabaseSchemaInitializer schemaInitializer;
    @MockitoBean UserServiceClient users;
    @MockitoBean EventAnalyticsService analytics;
    @MockitoBean VoucherService vouchers;

    String a;

    @BeforeEach
    void data() {
        assignments.deleteAll();
        events.deleteAll();
        callerIds.clear();
        when(users.getOrganizerEligibility(anyString())).thenAnswer(call -> new OrganizerEligibilityResponse(true, "u-" + call.getArgument(0, String.class).replaceFirst("^kc-", ""), null, null));
        a = events.save(Event.builder().title("A").build()).getId();
        Event event = events.findById(a).orElseThrow();
        assignments.save(StaffAssignment.builder().event(event).userId("u-owner").role(StaffRole.OWNER).status(StaffAssignmentStatus.ACTIVE).build());
        assignments.save(StaffAssignment.builder().event(event).userId("u-rev").role(StaffRole.MANAGER).status(StaffAssignmentStatus.REVOKED).build());
        when(vouchers.getVouchersByEventId(anyString())).thenReturn(List.of(VoucherResponse.builder().id("v").code("CODE10").build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        Jwt jwt = Jwt.withTokenValue("kc-" + who).header("alg", "none").subject("kc-" + who).claim("roles", List.of("ROLE_USER")).build();
        return request.with(authentication(converter.convert(jwt))).header("Authorization", "kc-" + who);
    }

    private int code(MockHttpServletRequestBuilder request, String who) throws Exception {
        return mvc.perform(as(request, who)).andReturn().getResponse().getStatus();
    }

    @Test
    void theOldBehaviourIsUnchangedWhileTheSwitchesAreOff() throws Exception {
        // analytics and voucher codes stayed readable by any signed-in person, and platform configuration writable by one
        assertThat(code(get("/api/events/" + a + "/analytics"), "stranger")).isEqualTo(200);
        mvc.perform(as(get("/api/events/" + a + "/vouchers"), "stranger")).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("CODE10"));
        assertThat(code(put("/api/events/admin/config/system-config").contentType(MediaType.APPLICATION_JSON).content("{}"), "stranger")).isNotIn(401, 403);
        // the owner and a platform-unaware reader behave as before: owner reads the members, a stranger does not
        assertThat(code(get("/api/events/" + a + "/members"), "owner")).isEqualTo(200);
        assertThat(code(get("/api/events/" + a + "/members"), "stranger")).isEqualTo(403);
    }

    @Test
    void theActiveFilterIsAlwaysOn() throws Exception {
        assertThat(code(get("/api/events/" + a + "/members/my-role"), "owner")).isEqualTo(200);
        assertThat(code(get("/api/events/" + a + "/members/my-role"), "rev")).isEqualTo(403);
        mvc.perform(as(get("/api/events/my-events"), "rev")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/events/my-events"), "owner")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
}
