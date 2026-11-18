package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.PayoutReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository bản đối soát chi trả theo từng sự kiện và organizer. */
public interface PayoutReconciliationRepository extends JpaRepository<PayoutReconciliation, String> {
    /** Liệt kê mọi bản đối soát của một organizer. */
    List<PayoutReconciliation> findAllByOrganizerUserId(String organizerUserId);
    /** Tìm bản đối soát của một sự kiện thuộc organizer. */
    Optional<PayoutReconciliation> findByEventIdAndOrganizerUserId(String eventId, String organizerUserId);
    /** Lấy bản đối soát theo sự kiện và organizer, có khóa ghi bi quan để tránh rút tiền đồng thời. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PayoutReconciliation r WHERE r.eventId=:eventId AND r.organizerUserId=:organizerId")
    Optional<PayoutReconciliation> findForUpdate(@Param("eventId") String eventId, @Param("organizerId") String organizerId);
}
