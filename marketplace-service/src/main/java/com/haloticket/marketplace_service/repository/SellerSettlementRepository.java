package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.SellerSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerSettlementRepository extends JpaRepository<SellerSettlement, String> {
    Optional<SellerSettlement> findByMarketplaceTransactionId(String marketplaceTransactionId);
}
