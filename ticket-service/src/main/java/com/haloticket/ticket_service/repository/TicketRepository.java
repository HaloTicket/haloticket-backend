package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, String> {
    List<Ticket> findByBookingId(String bookingId);
}
