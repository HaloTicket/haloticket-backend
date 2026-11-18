package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Kho lưu hồ sơ eKYC của người dùng. */
@Repository
public interface EkycProfileRepository extends JpaRepository<EkycProfile, String> {
    /**
     * Lấy hồ sơ eKYC của một người dùng.
     *
     * @param userId id người dùng
     * @return hồ sơ nếu có
     */
    Optional<EkycProfile> findByUserId(String userId);
    /**
     * Kiểm tra số giấy tờ (đã băm) đã được người dùng khác dùng chưa.
     *
     * @param hash mã băm số giấy tờ
     * @param userId id người dùng đang xác minh, bị loại khỏi phép kiểm tra
     * @return {@code true} nếu đã có người khác dùng
     */
    boolean existsByDocumentNumberHashAndUserIdNot(String hash, String userId);
    /**
     * Kiểm tra có hồ sơ nào mang số giấy tờ (đã băm) này ở trạng thái đã cho hay không.
     *
     * @param documentNumberHash mã băm số giấy tờ
     * @param status trạng thái xác minh cần khớp
     * @return {@code true} nếu tồn tại
     */
    boolean existsByDocumentNumberHashAndStatus(String documentNumberHash, EkycVerificationStatus status);
}
