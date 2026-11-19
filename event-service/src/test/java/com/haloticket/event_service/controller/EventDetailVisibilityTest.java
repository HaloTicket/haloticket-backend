package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.service.EventAccessService;
import com.haloticket.event_service.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Chi tiết sự kiện: đã duyệt thì công khai, nháp/chờ duyệt/bị từ chối chỉ ban tổ chức và admin nền tảng được xem. */
class EventDetailVisibilityTest {

    private final EventService events = mock(EventService.class);
    private final EventAccessService access = mock(EventAccessService.class);
    private final EventController controller = new EventController(events, access);

    private static EventResponse event(EventStatus status, String approval) {
        return EventResponse.builder().id("e1").eventStatus(status).eventApprovalStatus(approval).build();
    }

    private HttpStatus statusFor(EventResponse event, String authorization) {
        when(events.getEventById("e1")).thenReturn(event);
        return HttpStatus.valueOf(controller.getEventById("e1", authorization).getStatusCode().value());
    }

    @Test
    void approvedEventIsPublic() {
        assertEquals(HttpStatus.OK, statusFor(event(EventStatus.LIVE, "APPROVED"), null));
        assertEquals(HttpStatus.OK, statusFor(event(EventStatus.APPROVED, "UPDATE_PENDING"), null));
        assertEquals(HttpStatus.OK, statusFor(event(EventStatus.COMPLETED, "APPROVED"), null));
    }

    @Test
    void unpublishedEventIsHiddenFromAnonymousAndStrangers() {
        for (EventResponse draft : new EventResponse[]{
                event(EventStatus.DRAFT, "DRAFT"), event(EventStatus.PENDING, "PENDING"), event(EventStatus.DRAFT, "REJECTED")}) {
            assertEquals(HttpStatus.NOT_FOUND, statusFor(draft, null));
            when(access.forCaller("e1", "Bearer x")).thenReturn(new EventAccess("e1", "u9", false, null, null, false, Set.of()));
            assertEquals(HttpStatus.NOT_FOUND, statusFor(draft, "Bearer x"));
        }
    }

    @Test
    void unpublishedEventIsVisibleToItsTeamAndPlatformAdmin() {
        when(access.forCaller("e1", "Bearer owner")).thenReturn(new EventAccess("e1", "u1", false, StaffRole.OWNER, "a1", false, Set.of()));
        when(access.forCaller("e1", "Bearer admin")).thenReturn(new EventAccess("e1", "u2", true, null, null, false, Set.of()));
        assertEquals(HttpStatus.OK, statusFor(event(EventStatus.DRAFT, "DRAFT"), "Bearer owner"));
        assertEquals(HttpStatus.OK, statusFor(event(EventStatus.PENDING, "PENDING"), "Bearer admin"));
    }

    @Test
    void rejectedTokenOnUnpublishedEventIsTreatedAsNotFound() {
        when(access.forCaller("e1", "Bearer bad")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        assertEquals(HttpStatus.NOT_FOUND, statusFor(event(EventStatus.DRAFT, "DRAFT"), "Bearer bad"));
    }

    @Test
    void missingEventIsNotFound() {
        when(events.getEventById("e1")).thenReturn(null);
        ResponseEntity<EventResponse> response = controller.getEventById("e1", null);
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
    }
}
