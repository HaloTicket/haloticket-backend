package com.haloticket.payment_service.repository;
import com.haloticket.payment_service.entity.PayoutPolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PayoutPolicyAuditRepository extends JpaRepository<PayoutPolicyAudit,String> {
    List<PayoutPolicyAudit> findTop500ByOrderByChangedAtDesc();
}
