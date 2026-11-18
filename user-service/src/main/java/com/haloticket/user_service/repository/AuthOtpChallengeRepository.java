package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

/** Kho lưu các thử thách OTP đăng ký và đặt lại mật khẩu. */
public interface AuthOtpChallengeRepository extends JpaRepository<AuthOtpChallenge, String> {
    /**
     * Tìm thử thách theo id và khóa bản ghi để ghi (pessimistic write), tránh hai yêu cầu cùng xác minh một OTP.
     *
     * @param id mã thử thách
     * @return thử thách nếu có
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthOtpChallenge c where c.id = :id")
    Optional<AuthOtpChallenge> findByIdForUpdate(@Param("id") String id);

    /**
     * Tìm thử thách đã tạo trước đó với cùng khóa idempotency, mục đích và đối tượng nhận OTP.
     *
     * @param idempotencyKey khóa chống gửi trùng
     * @param purpose mục đích OTP
     * @param subject đối tượng nhận OTP (email hoặc định danh)
     * @return thử thách nếu có
     */
    Optional<AuthOtpChallenge> findByIdempotencyKeyAndPurposeAndSubject(
            String idempotencyKey, OtpPurpose purpose, String subject);
}
