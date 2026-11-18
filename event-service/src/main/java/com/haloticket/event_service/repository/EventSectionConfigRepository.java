package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.EventSectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Truy vấn cấu hình khu vực (section) của sự kiện {@code EventSectionConfig}. */
@Repository
public interface EventSectionConfigRepository extends JpaRepository<EventSectionConfig, String> {
}
