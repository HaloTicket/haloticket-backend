package com.haloticket.user_service.repository;

import com.haloticket.user_service.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;

import java.util.List;
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

    // role and status travel as parameters, never as text inside the query
    @Query("select count(u) from User u join u.roles r where r = :role and u.status = :status")
    long countByRoleAndStatus(@Param("role") Role role, @Param("status") UserStatus status);

    @Query("select r, count(u) from User u join u.roles r group by r")
    List<Object[]> countGroupedByRole();

    @Query("select u.status, count(u) from User u group by u.status")
    List<Object[]> countGroupedByStatus();
}
