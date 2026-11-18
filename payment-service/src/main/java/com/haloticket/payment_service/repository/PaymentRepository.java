package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository truy cập bảng {@code payments}. */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {
    /** Lấy thanh toán mới nhất của một booking theo mã booking. */
    Optional<Payment> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);
    /** Lấy thanh toán mới nhất theo mã đặt chỗ ({@code bookingCode}). */
    Optional<Payment> findFirstByBookingCodeOrderByCreatedAtDesc(String bookingCode);
    /** Lấy mọi thanh toán của một tập mã booking, dùng để tra cứu hàng loạt. */
    List<Payment> findAllByBookingIdIn(List<String> bookingIds);
}
