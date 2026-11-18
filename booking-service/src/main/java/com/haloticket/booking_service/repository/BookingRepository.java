package com.haloticket.booking_service.repository;

import com.haloticket.booking_service.entity.Booking;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.haloticket.booking_service.enums.BookingStatus;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** Truy vấn đơn đặt vé: tra cứu, khóa bi quan khi cập nhật, thống kê doanh thu và số liệu phục vụ đối soát, báo cáo. */
@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {
       /** Dữ kiện báo cáo của một đơn: sự kiện, tổng số vé và trạng thái. */
       interface ReportFact {
              /** Mã đơn. */
              String getBookingId();
              /** Mã sự kiện của đơn. */
              String getEventId();
              /** Tổng số vé của các dòng vé trong đơn. */
              Long getTickets();
              /** Trạng thái đơn. */
              BookingStatus getStatus();
       }
       /** Dữ kiện tài chính của một đơn đã thanh toán. */
       interface FinanceFact {
              /** Mã đơn. */
              String getBookingId();
              /** Tổng tiền của đơn. */
              Double getAmount();
       }
       /** Lấy mã đơn và tổng tiền của các đơn {@code PAID} của một sự kiện. */
       @Query("SELECT b.id AS bookingId, b.totalAmount AS amount FROM Booking b WHERE b.eventId=:eventId AND b.status='PAID'")
       List<FinanceFact> findPaidFinanceFactsByEventId(@Param("eventId") String eventId);

       /** Gộp theo đơn để lấy sự kiện, tổng số vé và trạng thái cho danh sách mã đơn. */
       @Query("SELECT b.id AS bookingId, b.eventId AS eventId, COALESCE(SUM(i.quantity), 0) AS tickets, b.status AS status " +
              "FROM Booking b LEFT JOIN b.items i WHERE b.id IN :bookingIds GROUP BY b.id, b.eventId, b.status")
       List<ReportFact> findReportFactsByBookingIds(@Param("bookingIds") Collection<String> bookingIds);

       /** Đơn đã thanh toán có thể thiếu vé: mã, mã hiển thị, thời điểm thanh toán và số vé đáng lẽ phải có. */
       interface PaidCandidate {
              /** Mã đơn. */
              String getBookingId();
              /** Mã đơn hiển thị cho khách. */
              String getBookingCode();
              /** Thời điểm thanh toán (lấy từ {@code updatedAt}). */
              LocalDateTime getPaidAt();
              /** Tổng số vé theo các dòng vé của đơn. */
              Long getExpectedTickets();
       }

       /** Các đơn đáng lẽ đã có vé (PAID, CONFIRMED) được thanh toán trong khoảng [from, to), cũ nhất trước. */
       @Query("SELECT b.id AS bookingId, b.bookingCode AS bookingCode, b.updatedAt AS paidAt, COALESCE(SUM(i.quantity), 0) AS expectedTickets " +
              "FROM Booking b LEFT JOIN b.items i " +
              "WHERE b.status IN :statuses " +
              "AND b.updatedAt >= :from AND b.updatedAt < :to " +
              "GROUP BY b.id, b.bookingCode, b.updatedAt ORDER BY b.updatedAt ASC, b.id ASC")
       List<PaidCandidate> findCandidatesWithStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                                    @Param("statuses") Collection<BookingStatus> statuses, Pageable pageable);

       /** Một đơn đã thanh toán dùng để thống kê: người mua, thời điểm thanh toán, tiền và số vé. */
       interface PaidOrderRow {
              /** Mã người mua. */
              String getCustomerId();
              /** Thời điểm thanh toán (lấy từ {@code updatedAt}). */
              LocalDateTime getPaidAt();
              /** Tổng tiền của đơn. */
              Double getAmount();
              /** Tổng số vé của đơn. */
              Long getTickets();
       }

       /** Các đơn đã thanh toán (trạng thái do bên gọi chọn) của một sự kiện trong khoảng [from, to). Cộng dồn bằng Java để các mốc ngày theo múi giờ của bên gọi. */
       @Query("SELECT b.customerId AS customerId, b.updatedAt AS paidAt, b.totalAmount AS amount, COALESCE(SUM(i.quantity), 0) AS tickets " +
              "FROM Booking b LEFT JOIN b.items i " +
              "WHERE b.eventId = :eventId AND b.status IN :statuses AND b.updatedAt >= :from AND b.updatedAt < :to " +
              "GROUP BY b.id, b.customerId, b.updatedAt, b.totalAmount")
       List<PaidOrderRow> findPaidOrderRows(@Param("eventId") String eventId, @Param("statuses") Collection<BookingStatus> statuses,
                                            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

       /** Lấy các đơn PAID hoặc CONFIRMED thanh toán trong khoảng [from, to), cũ nhất trước, theo từng trang. */
       default List<PaidCandidate> findPaidCandidates(LocalDateTime from, LocalDateTime to, Pageable pageable) {
              return findCandidatesWithStatus(from, to, List.of(BookingStatus.PAID, BookingStatus.CONFIRMED), pageable);
       }

       /** Đọc đơn kèm khóa ghi bi quan để tránh hai luồng xử lý thanh toán cùng một đơn. */
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT b FROM Booking b WHERE b.id = :id")
       Optional<Booking> findByIdForUpdate(@Param("id") String id);

       /** Tìm đơn theo mã hiển thị cho khách. */
       Optional<Booking> findByBookingCode(String bookingCode);

       /** Cố ý dùng cập nhật hàng loạt: nó không kích hoạt {@code @PreUpdate}, nên {@code updated_at} (thời điểm thanh toán mà đối soát đọc) được giữ nguyên. */
       @Modifying
       @Transactional
       @Query("UPDATE Booking b SET b.lastReissueAt = :now, b.reissueAttempts = COALESCE(b.reissueAttempts, 0) + 1 WHERE b.id = :id")
       int markReissueRequested(@Param("id") String id, @Param("now") LocalDateTime now);
       /** Lấy các đơn theo trạng thái đã quá hạn giữ chỗ trước mốc {@code dateTime}, có khóa ghi bi quan. */
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       List<Booking> findByStatusAndExpiredAtBefore(BookingStatus status, LocalDateTime dateTime);

       /** Đếm số đơn của một người mua đã dùng voucher với các trạng thái cho trước. */
       long countByCustomerIdAndVoucherIdAndStatusIn(String customerId, String voucherId, Collection<BookingStatus> statuses);

       /** Số đơn đã thanh toán của một người mua có dùng voucher (tính vào giới hạn số đơn mỗi người mua). */
       default long countByCustomerIdAndVoucherId(String customerId, String voucherId) {
              return countByCustomerIdAndVoucherIdAndStatusIn(customerId, voucherId, List.of(BookingStatus.PAID, BookingStatus.CONFIRMED));
       }

       /** Đếm số người mua khác nhau có đơn {@code PAID} của sự kiện. */
       @Query("SELECT COUNT(DISTINCT b.customerId) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long countUniqueBuyersByEventId(@Param("eventId") String eventId);

       /** Tổng doanh thu các đơn {@code PAID} của sự kiện. */
       @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b WHERE b.eventId = :eventId AND b.status = 'PAID'")
       double sumRevenueByEventId(@Param("eventId") String eventId);

       /** Tổng số vé của các đơn {@code PAID} của sự kiện. */
       @Query("SELECT COALESCE(SUM(i.quantity), 0) FROM Booking b JOIN b.items i WHERE b.eventId = :eventId AND b.status = 'PAID'")
       long sumTicketsSoldByEventId(@Param("eventId") String eventId);

       /** Tìm đơn của sự kiện, lọc tùy chọn theo suất chiếu, tìm gần đúng không phân biệt hoa thường theo mã đơn, tên hoặc email khách. */
       @Query("SELECT DISTINCT b FROM Booking b WHERE b.eventId = :eventId AND " +
                     "(:showtimeId IS NULL OR :showtimeId = '' OR b.showtimeId = :showtimeId) AND " +
                     "(LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
                     "LOWER(b.customerEmail) LIKE LOWER(CONCAT('%', :search, '%')))")
       Page<Booking> findByEventIdAndSearchAndShowtimeId(
                     @Param("eventId") String eventId,
                     @Param("search") String search,
                     @Param("showtimeId") String showtimeId,
                     Pageable pageable);
}
