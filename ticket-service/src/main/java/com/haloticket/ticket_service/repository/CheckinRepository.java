package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Checkin;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CheckinRepository extends JpaRepository<Checkin, String> {
    List<Checkin> findByEventIdOrderByCheckedInAtDesc(String eventId);
    
    List<Checkin> findByEventIdAndTicket_ShowtimeIdOrderByCheckedInAtDesc(String eventId, String showtimeId);

    Optional<Checkin> findByTicketId(String ticketId);
}
