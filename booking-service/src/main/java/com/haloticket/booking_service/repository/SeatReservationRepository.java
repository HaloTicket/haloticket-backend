package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.SeatReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.haloticket.booking_service.entity.Booking;
import java.util.List;

@Repository
public interface SeatReservationRepository extends JpaRepository<SeatReservation, String> {
    List<SeatReservation> findByBooking(Booking booking);
}
