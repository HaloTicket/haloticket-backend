package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.EkycAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface EkycAttemptRepository extends JpaRepository<EkycAttempt, String> {
    long countByUserIdAndCreatedAtAfter(String userId, Instant since);
    Optional<EkycAttempt> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    Optional<EkycAttempt> findFirstByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
