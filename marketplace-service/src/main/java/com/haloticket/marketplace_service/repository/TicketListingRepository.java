package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import com.haloticket.marketplace_service.dto.ResaleEventSummary;
/** Repository tin đăng bán lại vé. */
public interface TicketListingRepository extends JpaRepository<TicketListing,String> {
    /**
     * Lấy tin theo mã kèm khóa ghi bi quan để chặn thay đổi đồng thời (giữ chỗ, hủy, mua).
     *
     * @param id mã tin
     * @return tin nếu tồn tại
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from TicketListing l where l.id = :id")
    Optional<TicketListing> findByIdForUpdate(@Param("id") String id);
    /**
     * Lấy các tin đã công bố, đúng trạng thái và chưa hết hạn, để hiển thị công khai.
     *
     * @param status trạng thái cần lọc
     * @param now mốc thời gian hiện tại
     * @param pageable thông tin phân trang
     * @return trang tin
     */
    @Query("select l from TicketListing l where l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now)")
    Page<TicketListing> findPublicActive(@Param("status") ListingStatus status, @Param("now") java.time.Instant now, Pageable pageable);
    /**
     * Lấy các tin của một người bán.
     *
     * @param sellerId mã người bán
     * @param pageable thông tin phân trang
     * @return trang tin
     */
    Page<TicketListing> findBySellerId(String sellerId, Pageable pageable);
    /**
     * Tìm tin của một vé ở trạng thái cho trước.
     *
     * @param ticketId mã vé
     * @param status trạng thái tin
     * @return tin nếu có
     */
    Optional<TicketListing> findByTicketIdAndStatus(String ticketId, ListingStatus status);
    /**
     * Lấy các tin công khai (đã công bố, đúng trạng thái, chưa hết hạn) của một sự kiện.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái cần lọc
     * @param now mốc thời gian hiện tại
     * @param pageable thông tin phân trang
     * @return trang tin
     */
    @Query("select l from TicketListing l where l.eventId = :eventId and l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now)")
    Page<TicketListing> findPublicActiveByEventId(@Param("eventId") String eventId, @Param("status") ListingStatus status,
                                                   @Param("now") java.time.Instant now, Pageable pageable);
    /**
     * Tổng hợp theo sự kiện: số tin công khai và giá thấp nhất, sắp xếp giá thấp nhất tăng dần.
     *
     * @param status trạng thái cần lọc
     * @param now mốc thời gian hiện tại
     * @return danh sách tóm tắt sự kiện
     */
    @Query("select new com.haloticket.marketplace_service.dto.ResaleEventSummary(l.eventId, count(l), min(l.price)) " +
            "from TicketListing l where l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now) " +
            "group by l.eventId order by min(l.price) asc")
    List<ResaleEventSummary> summarizePublicEvents(@Param("status") ListingStatus status, @Param("now") java.time.Instant now);
    /**
     * Lấy các tin đang ở trạng thái cho trước mà thời hạn giữ chỗ đã qua (dùng để giải phóng giữ chỗ quá hạn).
     *
     * @param status trạng thái tin
     * @param now mốc thời gian hiện tại
     * @return danh sách tin quá hạn giữ chỗ
     */
    List<TicketListing> findByStatusAndReservedUntilBefore(ListingStatus status, java.time.Instant now);
    /**
     * Lấy mọi tin của một sự kiện.
     *
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @return trang tin
     */
    Page<TicketListing> findByEventId(String eventId, Pageable pageable);
    /**
     * Lấy các tin của một sự kiện theo trạng thái, có phân trang.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái tin
     * @param pageable thông tin phân trang
     * @return trang tin
     */
    Page<TicketListing> findByEventIdAndStatus(String eventId, ListingStatus status, Pageable pageable);
    /**
     * Đếm số tin của một sự kiện theo trạng thái.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái tin
     * @return số tin
     */
    long countByEventIdAndStatus(String eventId, ListingStatus status);
    /**
     * Đếm số tin theo trạng thái trên toàn hệ thống.
     *
     * @param status trạng thái tin
     * @return số tin
     */
    long countByStatus(ListingStatus status);
    /**
     * Lấy thời điểm tạo của tin cũ nhất theo trạng thái.
     *
     * @param status trạng thái tin
     * @return thời điểm cũ nhất, hoặc {@code null} nếu không có tin nào
     */
    @Query("select min(l.createdAt) from TicketListing l where l.status = :status")
    java.time.Instant oldestCreatedAtByStatus(@Param("status") ListingStatus status);
    /**
     * Lấy toàn bộ tin của một sự kiện theo trạng thái, không phân trang.
     *
     * @param eventId mã sự kiện
     * @param status trạng thái tin
     * @return danh sách tin
     */
    List<TicketListing> findByEventIdAndStatus(String eventId, ListingStatus status);
}
