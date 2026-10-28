package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.Permission;

import java.util.List;

/** What the caller may do with one event: the answer other services ask for (GET /api/events/{id}/members/my-access). */
public record EventAccessResponse(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                                  List<String> permissions) {

    public static EventAccessResponse from(EventAccess access) {
        List<String> names = access.permissions().stream().map(Permission::name).sorted().toList();
        return new EventAccessResponse(access.eventId(), access.userId(), access.platformAdmin(),
                access.role() == null ? null : access.role().name(), access.assignmentId(), names);
    }
}
