package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatRowRepository extends JpaRepository<SeatRow, String> {
}
