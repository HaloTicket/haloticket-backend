package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.enums.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface MarketplaceSagaRepository extends JpaRepository<MarketplaceSaga,String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from MarketplaceSaga s where s.id = :id")
    Optional<MarketplaceSaga> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    Optional<MarketplaceSaga> findByIdempotencyKey(String key);
    List<MarketplaceSaga> findTop50ByStateInAndNextRetryAtBeforeOrderByCreatedAtAsc(Collection<SagaState> states, Instant now);
}
