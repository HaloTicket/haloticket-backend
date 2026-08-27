package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.CheckInAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CheckInAttemptRepository extends JpaRepository<CheckInAttempt, String> {

}
