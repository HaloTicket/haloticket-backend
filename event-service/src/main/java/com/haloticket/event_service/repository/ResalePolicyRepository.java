package com.haloticket.event_service.repository;

import com.haloticket.event_service.entity.ResalePolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn chính sách bán lại vé {@code ResalePolicy} của sự kiện. */
public interface ResalePolicyRepository extends JpaRepository<ResalePolicy, String> {
    /** Lấy chính sách bán lại vé của một sự kiện (mỗi sự kiện tối đa một chính sách). */
    Optional<ResalePolicy> findByEventId(String eventId);
}
