package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.EkycAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** Kho lưu các lần thử xác minh eKYC. */
public interface EkycAttemptRepository extends JpaRepository<EkycAttempt, String> {
    /**
     * Đếm số lần thử của người dùng kể từ một thời điểm, dùng để giới hạn tần suất.
     *
     * @param userId id người dùng
     * @param since mốc thời gian bắt đầu đếm
     * @return số lần thử
     */
    long countByUserIdAndCreatedAtAfter(String userId, Instant since);
    /**
     * Lấy lần thử mới nhất của người dùng.
     *
     * @param userId id người dùng
     * @return lần thử mới nhất nếu có
     */
    Optional<EkycAttempt> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    /**
     * Tìm lần thử đã tạo với cùng khóa idempotency để trả lại kết quả cũ khi gửi trùng.
     *
     * @param userId id người dùng
     * @param idempotencyKey khóa chống gửi trùng
     * @return lần thử nếu có
     */
    Optional<EkycAttempt> findFirstByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
