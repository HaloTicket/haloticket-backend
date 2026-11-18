package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.EventTransferPolicyState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;

/** Repository bản sao chính sách chuyển nhượng theo sự kiện. */
public interface EventTransferPolicyStateRepository extends JpaRepository<EventTransferPolicyState, String> {
    /**
     * Lấy chính sách của sự kiện kèm khóa ghi bi quan để cập nhật an toàn khi có nhiều thông điệp đồng thời.
     *
     * @param eventId mã sự kiện
     * @return chính sách nếu đã có
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM EventTransferPolicyState s WHERE s.eventId = :eventId")
    Optional<EventTransferPolicyState> findByEventIdForUpdate(String eventId);
}
