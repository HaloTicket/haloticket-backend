package com.haloticket.ticket_service.authz;

import java.util.List;

/** Câu trả lời của event-service cho "my-access": vai trò của người gọi trong một sự kiện và các quyền đi kèm. */
public record EventAccessView(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                              List<String> permissions) {

    /** Cho biết người gọi có quyền {@code permission} hay không. */
    public boolean has(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
