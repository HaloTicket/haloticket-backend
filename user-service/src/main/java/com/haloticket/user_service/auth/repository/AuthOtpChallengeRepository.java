package com.haloticket.user_service.auth.repository;

import com.haloticket.user_service.auth.domain.AuthOtpChallenge;
import com.haloticket.user_service.auth.domain.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthOtpChallengeRepository extends JpaRepository<AuthOtpChallenge, String> {
    Optional<AuthOtpChallenge> findByIdempotencyKeyAndPurposeAndSubject(
            String idempotencyKey, OtpPurpose purpose, String subject);
}
