package com.haloticket.booking_service.authz;

import java.util.List;

/** What event-service answers for "my-access": the caller's role in one event and the permissions that come with it. */
public record EventAccessView(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                              List<String> permissions) {

    public boolean has(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
