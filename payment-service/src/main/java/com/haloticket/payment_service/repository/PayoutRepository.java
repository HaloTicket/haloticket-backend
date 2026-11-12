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
    java.util.Optional<Payout> findByIdempotencyKey(String idempotencyKey);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Payout p WHERE p.id=:id")
    java.util.Optional<Payout> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Payout p WHERE p.idempotencyKey=:key")
    java.util.Optional<Payout> findByIdempotencyKeyForUpdate(@org.springframework.data.repository.query.Param("key") String key);
}
