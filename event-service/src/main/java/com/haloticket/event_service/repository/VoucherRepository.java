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

/** Truy vấn mã giảm giá {@code Voucher}. */
@Repository
public interface VoucherRepository extends JpaRepository<Voucher, String> {
    /** Lấy các voucher của một sự kiện, mới tạo nhất trước. */
    List<Voucher> findByEventIdOrderByCreatedAtDesc(String eventId);
    /** Tìm voucher theo mã. */
    Optional<Voucher> findByCode(String code);

    /** Khóa dòng voucher để hai thanh toán hoàn tất cùng lúc không thể cùng đọc một số lượt đã dùng. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.id = :id")
    Optional<Voucher> findByIdForUpdate(@Param("id") String id);
    /** Kiểm tra mã voucher đã tồn tại hay chưa. */
    boolean existsByCode(String code);
}
