package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository truy cập bảng hoàn tiền {@code Refund}. */
@Repository
public interface RefundRepository extends JpaRepository<Refund, String> {

}
