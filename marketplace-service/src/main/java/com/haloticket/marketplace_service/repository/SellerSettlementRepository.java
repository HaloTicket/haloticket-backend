package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.SellerSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repository khoản đối soát cho người bán. */
public interface SellerSettlementRepository extends JpaRepository<SellerSettlement, String> {
    /**
     * Tìm khoản đối soát của một giao dịch bán lại.
     *
     * @param marketplaceTransactionId mã giao dịch
     * @return khoản đối soát nếu có
     */
    Optional<SellerSettlement> findByMarketplaceTransactionId(String marketplaceTransactionId);
}
