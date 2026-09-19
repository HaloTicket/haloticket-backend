package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.ResalePolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResalePolicyRepository extends JpaRepository<ResalePolicy, String> {
    Optional<ResalePolicy> findByEventId(String eventId);
}
