package com.haloticket.marketplace_service.repository;

import com.haloticket.marketplace_service.entity.TicketTransfer;
import java.util.Optional;
import java.util.List;
import com.haloticket.marketplace_service.enums.TransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository lượt chuyển nhượng vé (tặng, bán lại). */
public interface TicketTransferRepository extends JpaRepository<TicketTransfer, String> {
    /**
     * Lấy lượt chuyển nhượng theo mã saga kèm khóa ghi bi quan.
     *
     * @param sagaId mã saga
     * @return lượt chuyển nhượng nếu có
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from TicketTransfer t where t.sagaId = :sagaId")
    Optional<TicketTransfer> findBySagaIdForUpdate(@org.springframework.data.repository.query.Param("sagaId") String sagaId);
    /**
     * Tìm lượt chuyển nhượng theo mã saga.
     *
     * @param sagaId mã saga
     * @return lượt chuyển nhượng nếu có
     */
    Optional<TicketTransfer> findBySagaId(String sagaId);
    /**
     * Lấy các lượt chuyển nhượng gửi tới một người dùng theo trạng thái, mới nhất trước.
     *
     * @param toUserId mã người nhận
     * @param status trạng thái
     * @return danh sách lượt chuyển nhượng
     */
    List<TicketTransfer> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, TransferStatus status);
    /**
     * Tìm lượt chuyển nhượng đầu tiên của một vé theo trạng thái.
     *
     * @param ticketId mã vé
     * @param status trạng thái
     * @return lượt chuyển nhượng nếu có
     */
    Optional<TicketTransfer> findFirstByTicketIdAndStatus(String ticketId, TransferStatus status);
    /**
     * Lấy các lượt chuyển nhượng của một sự kiện.
     *
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @return trang lượt chuyển nhượng
     */
    Page<TicketTransfer> findByEventId(String eventId, Pageable pageable);
    /**
     * Lấy các lượt chuyển nhượng của một sự kiện theo trạng thái.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái
     * @param pageable thông tin phân trang
     * @return trang lượt chuyển nhượng
     */
    Page<TicketTransfer> findByEventIdAndStatus(String eventId, TransferStatus status, Pageable pageable);
    /**
     * Đếm số lượt chuyển nhượng của một sự kiện theo trạng thái.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái
     * @return số lượt
     */
    long countByEventIdAndStatus(String eventId, TransferStatus status);
}
