package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface SeatZoneRepository extends JpaRepository<SeatZone, String> {
    List<SeatZone> findBySeatLayoutId(String seatLayoutId);

    @Modifying
    @Transactional
    void deleteBySeatLayoutId(String seatLayoutId);
}
