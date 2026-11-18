package com.haloticket.booking_service.repository;

import java.util.List;
import java.util.Optional;

import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Truy vấn ngoại lệ thanh toán ({@code PaymentException}) cần người xử lý. */
@Repository
public interface PaymentExceptionRepository extends JpaRepository<PaymentException, String> {
    /** Kiểm tra đã có ngoại lệ cho thanh toán và loại này chưa (chống ghi trùng). */
    boolean existsByPaymentIdAndKind(String paymentId, PaymentExceptionKind kind);

    /** Kiểm tra đã có ngoại lệ cho đơn và loại này chưa (dùng cho loại không có mã thanh toán). */
    boolean existsByBookingIdAndKind(String bookingId, PaymentExceptionKind kind);

    /** Tìm ngoại lệ đầu tiên của đơn theo loại và trạng thái. */
    Optional<PaymentException> findFirstByBookingIdAndKindAndStatus(String bookingId, PaymentExceptionKind kind,
                                                                             PaymentExceptionStatus status);

    /** Lấy tối đa 200 ngoại lệ theo loại và trạng thái. */
    List<PaymentException> findTop200ByKindAndStatus(PaymentExceptionKind kind, PaymentExceptionStatus status);

    /** Liệt kê ngoại lệ theo trạng thái, có phân trang. */
    Page<PaymentException> findByStatus(PaymentExceptionStatus status, Pageable pageable);

    /** Đếm ngoại lệ theo trạng thái. */
    long countByStatus(PaymentExceptionStatus status);
}
