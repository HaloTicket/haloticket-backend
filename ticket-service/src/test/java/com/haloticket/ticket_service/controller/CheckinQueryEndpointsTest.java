package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.authz.EventAccessView;
import com.haloticket.ticket_service.authz.EventScopeProperties;
import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.dto.response.CheckinPageDTO;
import com.haloticket.ticket_service.dto.response.CheckinSummaryDTO;
import com.haloticket.ticket_service.dto.response.CheckinTicketLookupDTO;
import com.haloticket.ticket_service.exception.GlobalExceptionHandler;
import com.haloticket.ticket_service.service.CheckinService;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call the summary, the search and the paged list of the door, and what the controller passes on. */
class CheckinQueryEndpointsTest {

    private static final List<String> CHECKIN = List.of("CHECKIN_SCAN", "CHECKIN_VIEW");
    private static final List<String> VIEW_ONLY = List.of("CHECKIN_VIEW");
    private static final List<String> NO_DOOR = List.of("ORDERS_VIEW", "ANALYTICS_VIEW");

    EventServiceClient events = mock(EventServiceClient.class);
    CheckinService checkins = mock(CheckinService.class);
    EventScopeProperties scope = new EventScopeProperties();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        scope.setInternalToken("service-secret");
        scope.setCacheSeconds(0);
        EventAccessClient access = new EventAccessClient(events, scope);
        mvc = MockMvcBuilders.standaloneSetup(new CheckinController(checkins, access))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(events.getMyAccess(anyString(), anyString())).thenAnswer(call -> {
            String event = call.getArgument(0);
            String token = call.getArgument(1);
            return switch (token) {
                case "staff-a" -> event.equals("A") ? view(event, "CHECKIN_STAFF", CHECKIN) : denied();
                case "viewer-a" -> event.equals("A") ? view(event, "VIEWER", VIEW_ONLY) : denied();
                case "sales-a" -> event.equals("A") ? view(event, "SALES", NO_DOOR) : denied();
                case "staff-b" -> event.equals("B") ? view(event, "CHECKIN_STAFF", CHECKIN) : denied();
                default -> denied();
            };
        });
        when(checkins.getSummary(anyString())).thenReturn(new CheckinSummaryDTO("A", 10, 4, List.of(), List.of(), LocalDateTime.now()));
        when(checkins.getCheckinPage(anyString(), any(), anyInt(), anyInt())).thenReturn(new CheckinPageDTO(List.of(), 0, 20, 0, 0));
        when(checkins.search(anyString(), any(), any(), anyInt(), anyBoolean())).thenReturn(List.<CheckinTicketLookupDTO>of());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static EventAccessView view(String event, String role, List<String> permissions) {
        return new EventAccessView(event, "u-" + role, false, role, "as-1", permissions);
    }

    private static EventAccessView denied() {
        throw new FeignException.Forbidden("forbidden", Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, StandardCharsets.UTF_8, null),
                new byte[0], Map.of());
    }

    private MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder builder, String token) {
        return token == null ? builder : builder.header("Authorization", token);
    }

    private int codeOf(String path, String token) throws Exception {
        return mvc.perform(with(get(path), token)).andReturn().getResponse().getStatus();
    }

    @Test
    void theSummaryIsOpenToDoorStaffAndRefusedToEveryoneElse() throws Exception {
        String path = "/api/tickets/checkin/events/A/summary";
        assertThat(codeOf(path, null)).isEqualTo(401);
        assertThat(codeOf(path, "stranger")).isEqualTo(403);
        assertThat(codeOf(path, "staff-b")).isEqualTo(403);   // staff of another event
        assertThat(codeOf(path, "sales-a")).isEqualTo(403);   // a member without the door permissions
        assertThat(codeOf(path, "staff-a")).isEqualTo(200);
        assertThat(codeOf(path, "viewer-a")).isEqualTo(200);
        mvc.perform(with(get(path), "staff-a")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(10)).andExpect(jsonPath("$.checkedIn").value(4));
    }

    @Test
    void theSearchNeedsTheDoorPermissionAndTheScannerFlagFollowsTheScanPermission() throws Exception {
        String path = "/api/tickets/checkin/events/A/search?q=abc";
        assertThat(codeOf(path, null)).isEqualTo(401);
        assertThat(codeOf(path, "stranger")).isEqualTo(403);
        assertThat(codeOf(path, "sales-a")).isEqualTo(403);
        assertThat(codeOf(path, "staff-b")).isEqualTo(403);
        verifyNoInteractions(checkins);

        assertThat(codeOf(path, "staff-a")).isEqualTo(200);
        verify(checkins).search(eq("A"), eq("abc"), isNull(), eq(20), eq(true));    // may scan: the hash may be returned

        assertThat(codeOf(path, "viewer-a")).isEqualTo(200);
        verify(checkins).search(eq("A"), eq("abc"), isNull(), eq(20), eq(false));   // view only: no hash
    }

    @Test
    void theSearchNeedsATextAndPassesTheShowtimeAndLimitOn() throws Exception {
        assertThat(codeOf("/api/tickets/checkin/events/A/search", "staff-a")).isEqualTo(200);   // no text: an empty answer, not an error
        assertThat(codeOf("/api/tickets/checkin/events/A/search?q=ab&showtimeId=st-1&limit=5", "staff-a")).isEqualTo(200);
        verify(checkins).search("A", "ab", "st-1", 5, true);
    }

    @Test
    void thePagedListNeedsCheckinViewAndPassesThePagingOn() throws Exception {
        String path = "/api/tickets/checkin/events/A/page?page=2&size=50&showtimeId=st-9";
        assertThat(codeOf(path, null)).isEqualTo(401);
        assertThat(codeOf(path, "sales-a")).isEqualTo(403);
        assertThat(codeOf(path, "staff-b")).isEqualTo(403);
        assertThat(codeOf(path, "staff-a")).isEqualTo(200);
        verify(checkins).getCheckinPage("A", "st-9", 2, 50);

        assertThat(codeOf("/api/tickets/checkin/events/A/page", "viewer-a")).isEqualTo(200);
        verify(checkins).getCheckinPage("A", null, 0, 20);   // defaults: first page of 20
    }

    @Test
    void theOldListEndpointStillAnswers() throws Exception {
        assertThat(codeOf("/api/tickets/checkin/events/A", "staff-a")).isEqualTo(200);
        assertThat(codeOf("/api/tickets/checkin/events/A", "sales-a")).isEqualTo(403);
    }
}
