package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Truy vấn phân công nhân sự {@code StaffAssignment} của sự kiện (chủ sự kiện, quản lý, nhân viên check-in...). */
@Repository
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, String> {
    /** Lấy mọi phân công của một sự kiện. */
    List<StaffAssignment> findByEventId(String eventId);
    /** Tìm phân công của một người dùng trong sự kiện; lỗi nếu có nhiều hơn một dòng. */
    Optional<StaffAssignment> findByEventIdAndUserId(String eventId, String userId);
    /** Tìm phân công theo email được mời trong sự kiện; lỗi nếu có nhiều hơn một dòng. */
    Optional<StaffAssignment> findByEventIdAndEmail(String eventId, String email);
    /** Lấy mọi phân công theo email trong sự kiện, kể cả khi bị trùng. */
    List<StaffAssignment> findAllByEventIdAndEmail(String eventId, String email);
    /** Lấy mọi phân công của người dùng trong sự kiện, kể cả khi bị trùng. */
    List<StaffAssignment> findAllByEventIdAndUserId(String eventId, String userId);
    /** Tìm phân công theo mã băm của token lời mời (token gốc không được lưu). */
    Optional<StaffAssignment> findByInvitationTokenHash(String invitationTokenHash);

    /** Đếm số lời mời đã gửi cho sự kiện kể từ mốc {@code since}: mỗi phân công tính 1 lần gửi đầu cộng số lần gửi lại. */
    @Query("select coalesce(sum(1 + coalesce(a.invitationResendCount, 0)), 0) from StaffAssignment a "
            + "where a.event.id = :eventId and a.invitedAt > :since")
    long invitationsSentSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    /** Lấy thời điểm gửi lời mời sớm nhất của sự kiện kể từ mốc {@code since}, hoặc null nếu chưa có. */
    @Query("select min(a.invitedAt) from StaffAssignment a where a.event.id = :eventId and a.invitedAt > :since")
    LocalDateTime firstInvitationSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);
    @Query("""
            select distinct a.userId from StaffAssignment a where a.event.id = :eventId
            and a.status = :status and a.role in :roles and a.userId > :after
            and (a.assignedAt is null or a.assignedAt <= :cutoff) order by a.userId
            """)
    List<String> findNotificationTeamIds(@Param("eventId") String eventId, @Param("status") StaffAssignmentStatus status,
            @Param("roles") List<StaffRole> roles, @Param("cutoff") LocalDateTime cutoff,
            @Param("after") String after, Pageable page);

    @Query("""
            select distinct a.userId from StaffAssignment a where a.event.id = :eventId
            and a.status = :status and a.role in :roles and a.userId > :after and trim(a.userId) <> ''
            and (:memberId = '' or a.userId = :memberId)
            and (a.assignedAt is null or a.assignedAt <= :cutoff) order by a.userId
            """)
    List<String> findAnnouncementStaff(@Param("eventId") String eventId, @Param("status") StaffAssignmentStatus status,
            @Param("roles") List<StaffRole> roles, @Param("cutoff") LocalDateTime cutoff,
            @Param("memberId") String memberId, @Param("after") String after, Pageable page);
}
