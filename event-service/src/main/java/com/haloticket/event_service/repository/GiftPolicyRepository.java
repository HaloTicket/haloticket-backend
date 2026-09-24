package com.haloticket.event_service.repository;
import com.haloticket.event_service.entity.GiftPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface GiftPolicyRepository extends JpaRepository<GiftPolicy, String> {
    Optional<GiftPolicy> findByEventId(String eventId);
}
