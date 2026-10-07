package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.enums.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, String> {
    List<Payout> findByStatus(PayoutStatus status);
    List<Payout> findByOrganizerUserId(String organizerUserId);
}
