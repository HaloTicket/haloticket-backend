package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.AdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Kho lưu nhật ký thao tác quản trị trên tài khoản người dùng. */
@Repository
public interface AdminAuditRepository extends JpaRepository<AdminAudit, String> {
}
