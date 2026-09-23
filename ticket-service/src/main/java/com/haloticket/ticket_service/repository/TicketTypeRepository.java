package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, String> {
    List<TicketType> findByEventId(String eventId);
    List<TicketType> findByShowtimeId(String showtimeId);
    List<TicketType> findByEventIdAndShowtimeId(String eventId, String showtimeId);

    @Modifying
    @Transactional
    void deleteByEventId(String eventId);
}
