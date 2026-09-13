package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, String> {
    List<Voucher> findByEventIdOrderByCreatedAtDesc(String eventId);
    Optional<Voucher> findByCode(String code);
    boolean existsByCode(String code);
}
