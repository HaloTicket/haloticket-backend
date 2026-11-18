package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.enums.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;
/** Repository lưu saga điều phối chuyển nhượng vé của chợ vé. */
public interface MarketplaceSagaRepository extends JpaRepository<MarketplaceSaga,String> {
    /**
     * Lấy saga theo mã kèm khóa ghi bi quan để chặn xử lý đồng thời.
     *
     * @param id mã saga
     * @return saga nếu tồn tại
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from MarketplaceSaga s where s.id = :id")
    Optional<MarketplaceSaga> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    /**
     * Tìm saga theo khóa idempotency để trả lại kết quả cũ khi client gửi lặp.
     *
     * @param key khóa idempotency
     * @return saga nếu đã tạo
     */
    Optional<MarketplaceSaga> findByIdempotencyKey(String key);
    /**
     * Lấy tối đa 50 saga ở các trạng thái cho trước đã đến hạn thử lại, cũ nhất trước.
     *
     * @param states các trạng thái cần xử lý
     * @param now mốc thời gian hiện tại
     * @return danh sách saga cần thử lại
     */
    List<MarketplaceSaga> findTop50ByStateInAndNextRetryAtBeforeOrderByCreatedAtAsc(Collection<SagaState> states, Instant now);
}
