package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.PayoutReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutReconciliationRepository extends JpaRepository<PayoutReconciliation, String> {
    List<PayoutReconciliation> findAllByOrganizerUserId(String organizerUserId);
    Optional<PayoutReconciliation> findByEventIdAndOrganizerUserId(String eventId, String organizerUserId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayoutReconciliation r WHERE r.eventId=:eventId AND r.organizerUserId=:organizerId")
    Optional<PayoutReconciliation> findForUpdate(@Param("eventId") String eventId, @Param("organizerId") String organizerId);
}
