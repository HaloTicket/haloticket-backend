package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.Payout;
import com.haloticket.payment_service.enums.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository các lệnh rút tiền ({@code Payout}). */
@Repository
public interface PayoutRepository extends JpaRepository<Payout, String> {
    /** Liệt kê các lệnh rút tiền theo trạng thái. */
    List<Payout> findByStatus(PayoutStatus status);
    /** Liệt kê các lệnh rút tiền của một organizer. */
    List<Payout> findByOrganizerUserId(String organizerUserId);
    /** Tìm lệnh rút tiền theo khóa idempotency, không khóa bản ghi. */
    java.util.Optional<Payout> findByIdempotencyKey(String idempotencyKey);
    /** Lấy lệnh rút tiền theo id và khóa ghi bi quan (PESSIMISTIC_WRITE) để chặn xử lý đồng thời. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Payout p WHERE p.id=:id")
    java.util.Optional<Payout> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    /** Lấy lệnh rút tiền theo khóa idempotency và khóa ghi bi quan (PESSIMISTIC_WRITE). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Payout p WHERE p.idempotencyKey=:key")
    java.util.Optional<Payout> findByIdempotencyKeyForUpdate(@org.springframework.data.repository.query.Param("key") String key);
}
