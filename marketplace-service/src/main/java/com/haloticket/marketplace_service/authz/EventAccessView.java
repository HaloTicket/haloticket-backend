package com.haloticket.marketplace_service.authz;

import java.util.List;

/** Câu trả lời của event-service cho {@code my-access}: vai trò của người gọi trong một sự kiện và các quyền đi kèm. */
public record EventAccessView(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                              List<String> permissions) {

    /** Kiểm tra người gọi có quyền {@code permission} hay không (danh sách quyền null coi như không có quyền nào). */
    public boolean has(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
