package com.haloticket.booking_service.authz;

import java.util.List;

/** Câu trả lời của event-service cho "my-access": vai trò của người gọi trong một sự kiện và các quyền đi kèm. */
public record EventAccessView(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                              List<String> permissions) {

    /**
     * @param permission tên quyền cần kiểm tra
     * @return {@code true} nếu danh sách quyền có chứa quyền đó
     */
    public boolean has(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
