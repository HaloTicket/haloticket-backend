package com.haloticket.event_service.authz;

import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

/**
 * Hàm duy nhất trả lời "người này được làm gì với sự kiện này", dựa trên staff_assignments, chỉ tính các dòng ACTIVE.
 * userId là id của user-service (cái staff_assignments lưu), không bao giờ là subject của Keycloak.
 */
@Service
@RequiredArgsConstructor
public class EventPermissions {

    private final StaffAssignmentRepository assignments;
    private final EventRepository events;

    /**
     * Tính quyền của một người với một sự kiện. Admin nền tảng có đủ quyền (vai trò OWNER); người có dòng phân công ACTIVE nhận quyền theo vai trò;
     * phân công không còn ACTIVE hoặc không có phân công thì không có quyền nào.
     * @param userId id người dùng trong user-service, có thể null
     * @param eventId mã sự kiện
     * @param platformAdmin người gọi có ROLE_ADMIN hay không
     * @return quyền của người đó với sự kiện
     * @throws ResponseStatusException HTTP 404 {@code EVENT_NOT_FOUND} khi không có sự kiện
     */
    public EventAccess forUser(String userId, String eventId, boolean platformAdmin) {
        events.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
        if (platformAdmin) {
            return new EventAccess(eventId, userId, true, StaffRole.OWNER, null, false, RolePermissions.all());
        }
        Optional<StaffAssignment> row = userId == null ? Optional.empty() : assignments.findByEventIdAndUserId(eventId, userId);
        if (row.isPresent()) {
            StaffAssignment assignment = row.get();
            if (assignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
                return new EventAccess(eventId, userId, false, null, assignment.getId(), true, Set.of());
            }
            return new EventAccess(eventId, userId, false, assignment.getRole(), assignment.getId(), false,
                    RolePermissions.of(assignment.getRole()));
        }
        return new EventAccess(eventId, userId, false, null, null, false, Set.of());
    }
}
