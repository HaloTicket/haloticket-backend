package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Kho các khu ghế của sơ đồ ghế. */
@Repository
public interface SeatZoneRepository extends JpaRepository<SeatZone, String> {
    /** Các khu ghế của một sơ đồ ghế. */
    List<SeatZone> findBySeatLayoutId(String seatLayoutId);

    /** Xóa mọi khu ghế của một sơ đồ ghế trong một giao dịch riêng. */
    @Modifying
    @Transactional
    void deleteBySeatLayoutId(String seatLayoutId);
}
