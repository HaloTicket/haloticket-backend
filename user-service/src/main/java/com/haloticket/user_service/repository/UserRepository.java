package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

@Repository
public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.keycloakId = :keycloakId")
    Optional<User> findForEkycUpdateByKeycloakId(@Param("keycloakId") String keycloakId);
    Optional<User> findByEmail(String email);
    Optional<User> findFirstByPhoneNumber(String phoneNumber);
    Optional<User> findByKeycloakId(String keycloakId);
}
