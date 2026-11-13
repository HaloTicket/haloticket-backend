package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.OrganizerPayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrganizerPayoutAccountRepository extends JpaRepository<OrganizerPayoutAccount, String> {
    Optional<OrganizerPayoutAccount> findByOrganizerUserId(String organizerUserId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM OrganizerPayoutAccount a WHERE a.organizerUserId=:userId")
    Optional<OrganizerPayoutAccount> findForUpdate(@org.springframework.data.repository.query.Param("userId") String userId);
}
