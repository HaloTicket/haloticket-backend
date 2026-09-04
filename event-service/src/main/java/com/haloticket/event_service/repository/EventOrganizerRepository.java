package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventOrganizer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventOrganizerRepository extends JpaRepository<EventOrganizer, String> {
    Optional<EventOrganizer> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    Optional<EventOrganizer> findByName(String name);
}
