package com.haloticket.event_service.dto.response;

import com.haloticket.event_service.authz.EventAccess;
import com.haloticket.event_service.authz.Permission;

import java.util.List;

/** Những gì người gọi được phép làm với một sự kiện: câu trả lời mà các service khác hỏi qua {@code GET /api/events/{id}/members/my-access}. */
public record EventAccessResponse(String eventId, String userId, boolean platformAdmin, String role, String assignmentId,
                                  List<String> permissions) {

    /**
     * Chuyển kết quả kiểm tra quyền nội bộ thành phản hồi API, với tên các quyền được sắp xếp.
     * @param access quyền truy cập đã tính cho người gọi
     * @return phản hồi chứa vai trò và danh sách quyền
     */
    public static EventAccessResponse from(EventAccess access) {
        List<String> names = access.permissions().stream().map(Permission::name).sorted().toList();
        return new EventAccessResponse(access.eventId(), access.userId(), access.platformAdmin(),
                access.role() == null ? null : access.role().name(), access.assignmentId(), names);
    }
}
