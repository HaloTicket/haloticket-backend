package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Kho sơ đồ ghế của sự kiện (bản nháp và bản đã xuất bản). */
@Repository
public interface SeatLayoutRepository extends JpaRepository<SeatLayout, String> {
    /** Mọi sơ đồ ghế của một sự kiện. */
    List<SeatLayout> findByEventId(String eventId);
    /** Sơ đồ ghế cập nhật gần nhất của sự kiện, bất kể trạng thái. */
    Optional<SeatLayout> findFirstByEventIdOrderByUpdatedAtDesc(String eventId);
    /** Sơ đồ ghế cập nhật gần nhất của sự kiện theo trạng thái (ví dụ đã xuất bản). */
    Optional<SeatLayout> findFirstByEventIdAndStatusOrderByUpdatedAtDesc(String eventId, SeatLayoutStatus status);
    /** Xóa mọi sơ đồ ghế của một sự kiện. */
    void deleteByEventId(String eventId);
}
