package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import com.haloticket.event_service.enums.StaffAssignmentStatus;

/** Truy vấn bảng sự kiện {@code Event}. */
@Repository
public interface EventRepository extends JpaRepository<Event, String> {
    /** Nạp toàn bộ sự kiện kèm danh mục và nhà tổ chức trong một truy vấn, dùng cho báo cáo của admin. */
    @Query("SELECT DISTINCT e FROM Event e LEFT JOIN FETCH e.category LEFT JOIN FETCH e.organizers")
    List<Event> findAllForAdminReport();

    /**
     * Lấy các sự kiện mà người dùng có phân công ở trạng thái cho trước.
     * @param userId id người dùng
     * @param status trạng thái phân công cần lọc
     */
    @Query("SELECT DISTINCT e FROM Event e JOIN e.staffAssignments sa WHERE sa.userId = :userId AND sa.status = :status")
    List<Event> findEventsOfStaff(@Param("userId") String userId, @Param("status") StaffAssignmentStatus status);

    /** Các sự kiện mà người dùng có phân công {@code ACTIVE} với bất kỳ vai trò nào (gồm cả nhân viên check-in vì họ cần thấy sự kiện trong danh sách). */
    default List<Event> findMyEvents(String userId) {
        return findEventsOfStaff(userId, StaffAssignmentStatus.ACTIVE);
    }
}
