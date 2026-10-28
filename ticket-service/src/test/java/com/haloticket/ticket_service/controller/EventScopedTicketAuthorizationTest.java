package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.authz.EventAccessView;
import com.haloticket.ticket_service.authz.EventScopeProperties;
import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.exception.GlobalExceptionHandler;
import com.haloticket.ticket_service.service.CheckinService;
import com.haloticket.ticket_service.service.TicketService;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class EventScopedTicketAuthorizationTest {

    private static final List<String> OWNER = List.of("EVENT_EDIT", "POLICY_WRITE", "POLICY_READ", "VOUCHER_MANAGE", "MEMBERS_VIEW", "MEMBERS_MANAGE",
            "OWNER_TRANSFER", "FINANCE", "MARKETPLACE_MODERATE", "MARKETPLACE_VIEW", "ORDERS_VIEW", "ANALYTICS_VIEW", "CHECKIN_SCAN", "CHECKIN_VIEW");
    private static final List<String> MANAGER = List.of("POLICY_READ", "MEMBERS_VIEW", "MARKETPLACE_VIEW", "ORDERS_VIEW", "ANALYTICS_VIEW", "CHECKIN_SCAN", "CHECKIN_VIEW");
    private static final List<String> CHECKIN = List.of("CHECKIN_SCAN", "CHECKIN_VIEW");
    private static final String BODY = "{\"eventId\":\"A\",\"name\":\"x\"}";
    private static final String SCAN = "{\"qrCodeHash\":\"h\",\"eventId\":\"A\",\"staffAssignmentId\":\"spoofed\"}";

    EventServiceClient events = mock(EventServiceClient.class);
    TicketService tickets = mock(TicketService.class);
    CheckinService checkins = mock(CheckinService.class);
    EventScopeProperties scope = new EventScopeProperties();
    EventAccessClient access;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        scope.setEnabled(true);
        scope.getCatalog().setEnabled(true);
        scope.getCheckin().setEnabled(true);
        scope.setInternalToken("service-secret");
        scope.setCacheSeconds(0); // every request asks again, so a role change is seen at once
        access = new EventAccessClient(events, scope);
        mvc = MockMvcBuilders.standaloneSetup(new TicketController(tickets, access, scope), new CheckinController(checkins, access, scope))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // people: owner-a (OWNER of A), mgr-a (MANAGER of A), chk-a (CHECKIN_STAFF of A), owner-b (OWNER of B), root (platform admin)
        when(events.getMyAccess(anyString(), anyString())).thenAnswer(call -> {
            String event = call.getArgument(0);
            String token = call.getArgument(1);
            return switch (token) {
                case "owner-a" -> event.equals("A") ? view(event, "u-owner-a", false, "OWNER", "as-1", OWNER) : denied();
                case "mgr-a" -> event.equals("A") ? view(event, "u-mgr-a", false, "MANAGER", "as-2", MANAGER) : denied();
                case "chk-a" -> event.equals("A") ? view(event, "u-chk-a", false, "CHECKIN_STAFF", "as-3", CHECKIN) : denied();
                case "owner-b" -> event.equals("B") ? view(event, "u-owner-b", false, "OWNER", "as-4", OWNER) : denied();
                case "root" -> view(event, "u-root-0001", true, "OWNER", null, OWNER);
                default -> denied();
            };
        });
        when(tickets.createTicketType(any())).thenReturn(null);
    }

    private static EventAccessView view(String event, String user, boolean admin, String role, String assignment, List<String> permissions) {
        return new EventAccessView(event, user, admin, role, assignment, permissions);
    }

    private static EventAccessView denied() {
        throw new FeignException.Forbidden("forbidden", request(), new byte[0], Map.of());
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder builder, String token) {
        return token == null ? builder : builder.header("Authorization", token);
    }

    private int create(String token) throws Exception {
        return mvc.perform(with(post("/api/tickets/types").contentType(MediaType.APPLICATION_JSON).content(BODY), token)).andReturn().getResponse().getStatus();
    }

    private int scan(String token) throws Exception {
        return mvc.perform(with(post("/api/tickets/checkin").contentType(MediaType.APPLICATION_JSON).content(SCAN), token)).andReturn().getResponse().getStatus();
    }

    @Test
    void catalogWritesNeedTheEditPermissionOfThatEvent() throws Exception {
        assertThat(create(null)).isEqualTo(401);
        assertThat(create("stranger")).isEqualTo(403);
        assertThat(create("owner-b")).isEqualTo(403);   // owner of another event
        assertThat(create("mgr-a")).isEqualTo(403);     // member of this event without EVENT_EDIT
        assertThat(create("chk-a")).isEqualTo(403);
        assertThat(create("owner-a")).isEqualTo(201);
        assertThat(create("root")).isEqualTo(201);
        verify(tickets, times(2)).createTicketType(any());
    }

    @Test
    void seatLayoutsAndSyncFollowTheSameRule() throws Exception {
        for (String path : List.of("/api/tickets/seat-layouts/event/A/draft")) {
            assertThat(mvc.perform(with(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"), "owner-b")).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(with(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"), null)).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(with(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"), "owner-a")).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(mvc.perform(with(post("/api/tickets/seat-layouts/event/A/publish").contentType(MediaType.APPLICATION_JSON).content("{}"), "mgr-a")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(with(post("/api/tickets/types/sync/A").contentType(MediaType.APPLICATION_JSON).content("[]"), "owner-b")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(with(post("/api/tickets/types/sync/A").contentType(MediaType.APPLICATION_JSON).content("[]"), "owner-a")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theServiceSecretLetsEventServiceSyncWithoutAUserAndAWrongOneDoesNot() throws Exception {
        assertThat(mvc.perform(post("/api/tickets/types/sync/A").header("X-Internal-Token", "service-secret")
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/tickets/types/sync/A").header("X-Internal-Token", "guess")
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn().getResponse().getStatus()).isEqualTo(401);
        scope.setInternalToken("");
        assertThat(mvc.perform(post("/api/tickets/types/sync/A").header("X-Internal-Token", "")
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void theFullTypeListNeedsOrdersViewSoACheckinStaffMemberCannotReadIt() throws Exception {
        assertThat(mvc.perform(with(get("/api/tickets/types/event/A/all"), "chk-a")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(with(get("/api/tickets/types/event/A/all"), "mgr-a")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(with(get("/api/tickets/types/event/A/all"), "owner-b")).andReturn().getResponse().getStatus()).isEqualTo(403);
        // the public list stays public
        assertThat(mvc.perform(get("/api/tickets/types/event/A")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failClosedWhenEventServiceIsDown() throws Exception {
        doThrow(new RuntimeException("connection refused")).when(events).getMyAccess(anyString(), anyString());
        assertThat(create("owner-a")).isEqualTo(503);
        verifyNoInteractions(tickets);
    }

    @Test
    void anyEventRoleMayScanButOnlyInItsOwnEvent() throws Exception {
        assertThat(scan(null)).isEqualTo(401);
        assertThat(scan("stranger")).isEqualTo(403);
        assertThat(scan("owner-b")).isEqualTo(403);   // owner of another event
        verifyNoInteractions(checkins);
        for (String who : List.of("chk-a", "mgr-a", "owner-a")) assertThat(scan(who)).isEqualTo(200);
        assertThat(scan("root")).isEqualTo(200);
    }

    @Test
    void theStoredStaffIdIsTheVerifiedAssignmentNotTheRequestValue() throws Exception {
        scan("chk-a");
        scan("root");
        ArgumentCaptor<CheckinRequest> sent = ArgumentCaptor.forClass(CheckinRequest.class);
        verify(checkins, times(2)).processCheckin(sent.capture());
        assertThat(sent.getAllValues().get(0).getStaffAssignmentId()).isEqualTo("as-3");
        assertThat(sent.getAllValues().get(1).getStaffAssignmentId()).isEqualTo("PLATFORM_ADMIN:u-root-0");
    }

    @Test
    void theScanListNeedsCheckinView() throws Exception {
        assertThat(mvc.perform(with(get("/api/tickets/checkin/events/A"), "stranger")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(with(get("/api/tickets/checkin/events/A"), "owner-b")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(with(get("/api/tickets/checkin/events/A"), null)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(with(get("/api/tickets/checkin/events/A"), "chk-a")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anOutageDoesNotStopTheEntranceWithinFiveMinutesButNothingElse() throws Exception {
        assertThat(create("owner-a")).isEqualTo(201);                   // a catalog answer is cached too
        assertThat(scan("chk-a")).isEqualTo(200);                       // fills the cache
        doThrow(new RuntimeException("connection refused")).when(events).getMyAccess(anyString(), anyString());
        assertThat(scan("chk-a")).isEqualTo(200);                       // stale answer reused for check-in
        assertThat(scan("mgr-a")).isEqualTo(503);                       // never seen before: nothing to reuse
        scope.setStaleCheckinSeconds(0);
        assertThat(scan("chk-a")).isEqualTo(503);                       // too old
        // the stale answer is not used for catalog writes
        scope.setStaleCheckinSeconds(300);
        assertThat(create("owner-a")).isEqualTo(503);
    }

    @Test
    void withTheSwitchesOffNothingChanges() throws Exception {
        scope.setEnabled(false);
        assertThat(create(null)).isEqualTo(201);
        assertThat(scan(null)).isEqualTo(200);
        ArgumentCaptor<CheckinRequest> sent = ArgumentCaptor.forClass(CheckinRequest.class);
        verify(checkins).processCheckin(sent.capture());
        assertThat(sent.getValue().getStaffAssignmentId()).isEqualTo("spoofed");
        verifyNoInteractions(events);
    }

    @Test
    void oneGroupOnlyAffectsItsOwnEndpoints() throws Exception {
        scope.getCheckin().setEnabled(false);
        assertThat(scan("stranger")).isEqualTo(200);
        assertThat(create("stranger")).isEqualTo(403);
    }
}
