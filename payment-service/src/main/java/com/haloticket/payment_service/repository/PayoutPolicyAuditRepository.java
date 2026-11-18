package com.haloticket.payment_service.repository;
import com.haloticket.payment_service.entity.PayoutPolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
/** Repository nhật ký thay đổi chính sách chi trả cho organizer. */
public interface PayoutPolicyAuditRepository extends JpaRepository<PayoutPolicyAudit,String> {
    /** Lấy tối đa 500 bản ghi nhật ký mới nhất theo thời điểm thay đổi, mới nhất trước. */
    List<PayoutPolicyAudit> findTop500ByOrderByChangedAtDesc();
}
