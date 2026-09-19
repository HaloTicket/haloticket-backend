package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.StaffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, String> {
    List<StaffAssignment> findByEventId(String eventId);
    Optional<StaffAssignment> findByEventIdAndUserId(String eventId, String userId);
    Optional<StaffAssignment> findByEventIdAndEmail(String eventId, String email);
}
