package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.MarketplaceTransaction;
import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketplaceTransactionRepository extends JpaRepository<MarketplaceTransaction, String> {
    Optional<MarketplaceTransaction> findByPaymentReference(String paymentReference);
    Optional<MarketplaceTransaction> findBySagaId(String sagaId);
    Optional<MarketplaceTransaction> findByTicketListing_IdAndStatus(String listingId, MarketplaceTransactionStatus status);
}
