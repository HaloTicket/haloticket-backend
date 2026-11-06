package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.PaymentException;
import com.haloticket.booking_service.enums.PaymentExceptionKind;
import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentExceptionRepository extends JpaRepository<PaymentException, String> {
    boolean existsByPaymentIdAndKind(String paymentId, PaymentExceptionKind kind);

    boolean existsByBookingIdAndKind(String bookingId, PaymentExceptionKind kind);

    Page<PaymentException> findByStatus(PaymentExceptionStatus status, Pageable pageable);

    long countByStatus(PaymentExceptionStatus status);
}
