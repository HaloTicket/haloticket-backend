package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, String> {
    List<Voucher> findByEventIdOrderByCreatedAtDesc(String eventId);
    Optional<Voucher> findByCode(String code);

    /** Locks the row so two payments that finish together cannot both read the same used count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.id = :id")
    Optional<Voucher> findByIdForUpdate(@Param("id") String id);
    boolean existsByCode(String code);
}
