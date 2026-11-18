package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Kho ghế cụ thể trong sơ đồ ghế. */
@Repository
public interface SeatRepository extends JpaRepository<Seat, String> {

}
