package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatLayoutRepository extends JpaRepository<SeatLayout, String> {

}
