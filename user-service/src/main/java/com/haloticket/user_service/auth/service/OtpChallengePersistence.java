package com.haloticket.user_service.auth.service;

import com.haloticket.user_service.auth.domain.AuthOtpChallenge;
import com.haloticket.user_service.auth.repository.AuthOtpChallengeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OtpChallengePersistence {
    private final AuthOtpChallengeRepository repository;

    public OtpChallengePersistence(AuthOtpChallengeRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthOtpChallenge insert(AuthOtpChallenge challenge) {
        return repository.saveAndFlush(challenge);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(AuthOtpChallenge challenge) {
        repository.saveAndFlush(challenge);
    }
}
