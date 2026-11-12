package com.haloticket.payment_service.repository;
import com.haloticket.payment_service.entity.PayoutPolicyOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
public interface PayoutPolicyOverrideRepository extends JpaRepository<PayoutPolicyOverride,String> {
    Optional<PayoutPolicyOverride> findByScopeAndTargetId(String scope,String targetId);
    List<PayoutPolicyOverride> findAllByOrderByScopeAscTargetIdAsc();
}
