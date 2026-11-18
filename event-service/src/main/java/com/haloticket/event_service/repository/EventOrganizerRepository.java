package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventOrganizer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Truy vấn hồ sơ nhà tổ chức sự kiện {@code EventOrganizer}. */
@Repository
public interface EventOrganizerRepository extends JpaRepository<EventOrganizer, String> {
    /** Lấy hồ sơ nhà tổ chức tạo gần nhất của một người dùng. */
    Optional<EventOrganizer> findFirstByUserIdOrderByCreatedAtDesc(String userId);
    /** Tìm nhà tổ chức theo đúng tên. */
    Optional<EventOrganizer> findByName(String name);
}
