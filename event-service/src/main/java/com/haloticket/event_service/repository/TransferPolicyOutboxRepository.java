package com.haloticket.event_service.repository;
import com.haloticket.event_service.entity.TransferPolicyOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
public interface TransferPolicyOutboxRepository extends JpaRepository<TransferPolicyOutboxEvent, String> {
    @Query(value = "select * from transfer_policy_outbox where status = 'PENDING' and next_attempt_at <= :now order by created_at limit 50 for update skip locked", nativeQuery = true)
    List<TransferPolicyOutboxEvent> lockDue(@Param("now") Instant now);
}
