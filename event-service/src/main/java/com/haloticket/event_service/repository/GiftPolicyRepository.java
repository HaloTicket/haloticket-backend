package com.haloticket.event_service.repository;
import com.haloticket.event_service.entity.GiftPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
/** Truy vấn chính sách tặng vé {@code GiftPolicy} của sự kiện. */
public interface GiftPolicyRepository extends JpaRepository<GiftPolicy, String> {
    /** Lấy chính sách tặng vé của một sự kiện (mỗi sự kiện tối đa một chính sách). */
    Optional<GiftPolicy> findByEventId(String eventId);
}
