package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.PayoutQuote;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository các báo giá rút tiền ({@code PayoutQuote}). */
public interface PayoutQuoteRepository extends JpaRepository<PayoutQuote, String> {
    /** Lấy báo giá theo id và khóa ghi bi quan (PESSIMISTIC_WRITE) để chặn xử lý đồng thời. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT q FROM PayoutQuote q WHERE q.id=:id")
    java.util.Optional<PayoutQuote> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
