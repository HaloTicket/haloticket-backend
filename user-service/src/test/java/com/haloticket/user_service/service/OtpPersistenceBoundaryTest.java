package com.haloticket.user_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class OtpPersistenceBoundaryTest {

    @Test
    void insertRunsInAnIndependentTransaction() throws NoSuchMethodException {
        Method method = OtpChallengePersistence.class.getDeclaredMethod("insert",
                com.haloticket.user_service.entity.AuthOtpChallenge.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void expiryUpdateRunsInAnIndependentTransaction() throws NoSuchMethodException {
        Method method = OtpChallengePersistence.class.getDeclaredMethod("markExpired",
                com.haloticket.user_service.entity.AuthOtpChallenge.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
