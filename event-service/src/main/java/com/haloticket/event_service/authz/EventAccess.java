package com.haloticket.event_service.authz;

import com.haloticket.event_service.enums.StaffRole;

import java.util.Set;

/**
 * Những gì một người được làm với một sự kiện.
 * 
 * @param eventId        mã sự kiện
 * @param userId         id người dùng trong user-service, có thể null
 * @param platformAdmin  người gọi mang ROLE_ADMIN
 * @param role           vai trò đang hoạt động của người đó trong sự kiện, hoặc null (admin nền tảng được báo là OWNER)
 * @param assignmentId   dòng staff_assignments đang hoạt động; null với admin nền tảng
 * @param inactive       người đó có dòng phân công cho sự kiện nhưng không còn ACTIVE (đã bị gỡ)
 * @param permissions    tập quyền của người đó với sự kiện
 */
public record EventAccess(String eventId, String userId, boolean platformAdmin, StaffRole role, String assignmentId,
                          boolean inactive, Set<Permission> permissions) {

    /** Kiểm tra người này có quyền đã cho với sự kiện hay không. */
    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
