package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatZoneRepository extends JpaRepository<SeatZone, String> {

}
