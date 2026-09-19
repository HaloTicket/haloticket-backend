package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.enums.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
public interface MarketplaceSagaRepository extends JpaRepository<MarketplaceSaga,String> {
    Optional<MarketplaceSaga> findByIdempotencyKey(String key);
    List<MarketplaceSaga> findTop50ByStateInAndNextRetryAtBeforeOrderByCreatedAtAsc(Collection<SagaState> states, Instant now);
}
