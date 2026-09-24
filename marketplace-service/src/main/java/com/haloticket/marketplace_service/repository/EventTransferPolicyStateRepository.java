package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.EventTransferPolicyState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

public interface EventTransferPolicyStateRepository extends JpaRepository<EventTransferPolicyState, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EventTransferPolicyState s WHERE s.eventId = :eventId")
    Optional<EventTransferPolicyState> findByEventIdForUpdate(String eventId);
}
