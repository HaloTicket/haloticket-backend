package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.StaffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, String> {
    List<StaffAssignment> findByEventId(String eventId);
    Optional<StaffAssignment> findByEventIdAndUserId(String eventId, String userId);
    Optional<StaffAssignment> findByEventIdAndEmail(String eventId, String email);
    List<StaffAssignment> findAllByEventIdAndEmail(String eventId, String email);
    List<StaffAssignment> findAllByEventIdAndUserId(String eventId, String userId);
    Optional<StaffAssignment> findByInvitationTokenHash(String invitationTokenHash);

    @Query("select coalesce(sum(1 + coalesce(a.invitationResendCount, 0)), 0) from StaffAssignment a "
            + "where a.event.id = :eventId and a.invitedAt > :since")
    long invitationsSentSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);

    @Query("select min(a.invitedAt) from StaffAssignment a where a.event.id = :eventId and a.invitedAt > :since")
    LocalDateTime firstInvitationSince(@Param("eventId") String eventId, @Param("since") LocalDateTime since);
}
