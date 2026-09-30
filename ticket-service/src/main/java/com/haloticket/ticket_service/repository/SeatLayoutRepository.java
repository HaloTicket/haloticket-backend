package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeatLayoutRepository extends JpaRepository<SeatLayout, String> {
    List<SeatLayout> findByEventId(String eventId);
    Optional<SeatLayout> findFirstByEventIdOrderByUpdatedAtDesc(String eventId);
    Optional<SeatLayout> findFirstByEventIdAndStatusOrderByUpdatedAtDesc(String eventId, SeatLayoutStatus status);
    void deleteByEventId(String eventId);
}
