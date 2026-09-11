package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.enums.EkycVerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EkycProfileRepository extends JpaRepository<EkycProfile, String> {
    Optional<EkycProfile> findByUserId(String userId);
    boolean existsByDocumentNumberHashAndUserIdNot(String hash, String userId);
    boolean existsByDocumentNumberHashAndStatus(String documentNumberHash, EkycVerificationStatus status);
}
