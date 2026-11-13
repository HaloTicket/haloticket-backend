package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.PayoutQuote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutQuoteRepository extends JpaRepository<PayoutQuote, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT q FROM PayoutQuote q WHERE q.id=:id")
    java.util.Optional<PayoutQuote> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
