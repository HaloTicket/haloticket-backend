package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Truy vấn địa điểm tổ chức {@code Venue}. */
@Repository
public interface VenueRepository extends JpaRepository<Venue, Long> {
    /** Tìm địa điểm theo đúng tên, không phân biệt hoa thường. */
    Optional<Venue> findByNameIgnoreCase(String name);
}
