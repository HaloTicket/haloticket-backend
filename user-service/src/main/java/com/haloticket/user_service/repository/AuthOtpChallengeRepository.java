package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.AuthOtpChallenge;
import com.haloticket.user_service.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface AuthOtpChallengeRepository extends JpaRepository<AuthOtpChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthOtpChallenge c where c.id = :id")
    Optional<AuthOtpChallenge> findByIdForUpdate(@Param("id") String id);

    Optional<AuthOtpChallenge> findByIdempotencyKeyAndPurposeAndSubject(
            String idempotencyKey, OtpPurpose purpose, String subject);
}
