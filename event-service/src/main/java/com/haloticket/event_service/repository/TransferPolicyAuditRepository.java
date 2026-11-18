package com.haloticket.event_service.repository;
import com.haloticket.event_service.entity.TransferPolicyAudit;
import org.springframework.data.jpa.repository.JpaRepository;
/** Truy vấn nhật ký kiểm toán {@code TransferPolicyAudit} khi chính sách chuyển nhượng vé bị thay đổi. */
public interface TransferPolicyAuditRepository extends JpaRepository<TransferPolicyAudit, String> {}
