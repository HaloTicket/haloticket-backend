package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository truy cập bảng giao dịch thanh toán {@code PaymentTransaction}. */
@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {

}
