package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.haloticket.booking_service.enums.BookingStatus;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByStatusAndExpiredAtBefore(BookingStatus status, LocalDateTime dateTime);
}
