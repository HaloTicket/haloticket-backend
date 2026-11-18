package com.haloticket.event_service.repository;
import com.haloticket.event_service.entity.TransferPolicyOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
/** Truy vấn bảng outbox {@code TransferPolicyOutboxEvent} chứa các thay đổi chính sách chuyển nhượng chờ phát đi. */
public interface TransferPolicyOutboxRepository extends JpaRepository<TransferPolicyOutboxEvent, String> {
    /**
     * Lấy và khóa tối đa 50 bản ghi {@code PENDING} đã đến hạn thử lại, cũ nhất trước. Dùng {@code skip locked} để nhiều tiến trình phát không giành cùng một bản ghi.
     * @param now thời điểm hiện tại, so với {@code next_attempt_at}
     */
    @Query(value = "select * from transfer_policy_outbox where status = 'PENDING' and next_attempt_at <= :now order by created_at limit 50 for update skip locked", nativeQuery = true)
    List<TransferPolicyOutboxEvent> lockDue(@Param("now") Instant now);
}
