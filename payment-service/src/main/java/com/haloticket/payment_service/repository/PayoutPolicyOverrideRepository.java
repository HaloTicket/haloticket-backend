package com.haloticket.payment_service.repository;
import com.haloticket.payment_service.entity.PayoutPolicyOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
/** Repository các ngoại lệ chính sách chi trả áp dụng riêng theo phạm vi (organizer hoặc sự kiện). */
public interface PayoutPolicyOverrideRepository extends JpaRepository<PayoutPolicyOverride,String> {
    /** Tìm ngoại lệ theo phạm vi và mã đối tượng áp dụng. */
    Optional<PayoutPolicyOverride> findByScopeAndTargetId(String scope,String targetId);
    /** Liệt kê mọi ngoại lệ, sắp theo phạm vi rồi mã đối tượng tăng dần. */
    List<PayoutPolicyOverride> findAllByOrderByScopeAscTargetIdAsc();
}
