package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import com.haloticket.event_service.enums.StaffAssignmentStatus;

/** Truy vấn bảng sự kiện {@code Event}. */
@Repository
public interface EventRepository extends JpaRepository<Event, String>, JpaSpecificationExecutor<Event> {
    @Query("select count(s) > 0 from EventShowtime s where s.event.id = :eventId and s.id = :showtimeId")
    boolean hasNotificationShowtime(@Param("eventId") String eventId, @Param("showtimeId") String showtimeId);

    /**
     * Các sự kiện công khai: đã duyệt ({@code APPROVED} hoặc {@code LIVE}) và không riêng tư. Bản nháp, chờ duyệt, bị từ chối và sự kiện
     * riêng tư không bao giờ nằm trong danh sách công khai.
     */
    @Query("SELECT e FROM Event e WHERE e.eventStatus IN (com.haloticket.event_service.enums.EventStatus.APPROVED, com.haloticket.event_service.enums.EventStatus.LIVE) "
            + "AND (e.isPrivate IS NULL OR e.isPrivate = false)")
    List<Event> findPublicEvents();

    /** Tìm kiếm có phân trang theo đặc tả; nạp sẵn danh mục để dựng kết quả không phát sinh truy vấn thừa cho từng dòng. */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

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
