package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.AdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditRepository extends JpaRepository<AdminAudit, String> {
}
