package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.MarketplaceTransaction;
import com.haloticket.marketplace_service.enums.MarketplaceTransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;

/** Repository giao dịch mua vé bán lại. */
public interface MarketplaceTransactionRepository extends JpaRepository<MarketplaceTransaction, String> {
    /**
     * Tìm giao dịch theo mã tham chiếu thanh toán.
     *
     * @param paymentReference mã tham chiếu thanh toán
     * @return giao dịch nếu có
     */
    Optional<MarketplaceTransaction> findByPaymentReference(String paymentReference);
    /**
     * Tìm giao dịch gắn với một saga.
     *
     * @param sagaId mã saga
     * @return giao dịch nếu có
     */
    Optional<MarketplaceTransaction> findBySagaId(String sagaId);
    /**
     * Tìm giao dịch của một tin bán lại ở trạng thái cho trước.
     *
     * @param listingId mã tin bán lại
     * @param status trạng thái giao dịch
     * @return giao dịch nếu có
     */
    Optional<MarketplaceTransaction> findByTicketListing_IdAndStatus(String listingId, MarketplaceTransactionStatus status);
    /**
     * Tìm giao dịch đầu tiên của một người mua trên một tin bán lại, có trạng thái nằm trong tập cho trước.
     *
     * @param listingId mã tin bán lại
     * @param buyerId mã người mua
     * @param statuses các trạng thái chấp nhận
     * @return giao dịch nếu có
     */
    Optional<MarketplaceTransaction> findFirstByTicketListing_IdAndBuyerIdAndStatusIn(
            String listingId, String buyerId, Collection<MarketplaceTransactionStatus> statuses);
}
