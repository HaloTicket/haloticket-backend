package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Repository JPA cho tài khoản nhận tiền của ban tổ chức. */
@Repository
public interface OrganizerPayoutAccountRepository extends JpaRepository<OrganizerPayoutAccount, String> {
    /**
     * Tìm tài khoản nhận tiền theo mã người dùng của ban tổ chức.
     *
     * @param organizerUserId mã người dùng
     * @return tài khoản nếu có
     */
    Optional<OrganizerPayoutAccount> findByOrganizerUserId(String organizerUserId);
    /**
     * Tìm tài khoản và khóa bản ghi (pessimistic write) để cập nhật an toàn khi có nhiều yêu cầu đồng thời.
     *
     * @param userId mã người dùng của ban tổ chức
     * @return tài khoản nếu có
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM OrganizerPayoutAccount a WHERE a.organizerUserId=:userId")
    Optional<OrganizerPayoutAccount> findForUpdate(@org.springframework.data.repository.query.Param("userId") String userId);
}
