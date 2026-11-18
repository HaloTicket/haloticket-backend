package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Truy vấn các dòng vé ({@code BookingItem}) của đơn. */
@Repository
public interface BookingItemRepository extends JpaRepository<BookingItem, String> {
    /** Lấy các dòng vé thuộc một đơn. */
    List<BookingItem> findByBookingId(String bookingId);
}
