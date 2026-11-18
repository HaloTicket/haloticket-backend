package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Repository cho bảng {@code outbox_events} của payment-service, đồng thời chứa các truy vấn native
 * tổng hợp doanh thu, hoàn tiền và dòng tiền theo thời điểm thanh toán được xác nhận.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    /**
     * Lấy tối đa 50 sự kiện outbox theo trạng thái, cũ nhất trước, để publisher gửi lên Kafka.
     *
     * @param status trạng thái cần lọc
     * @return danh sách sự kiện, tối đa 50 phần tử
     */
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /** Dữ liệu một thanh toán thành công (một dòng cho mỗi booking) dùng cho đối soát doanh thu. */
    interface SuccessfulPaymentFact {
        /** @return mã booking */
        String getBookingId();
        /** @return mã thanh toán được chọn cho booking */
        String getPaymentId();
        /** @return thời điểm xác nhận thanh toán, lấy từ sự kiện {@code PAYMENT_COMPLETED} đầu tiên */
        LocalDateTime getConfirmedAt();
        /** @return số tiền thanh toán (doanh thu) */
        Double getRevenue();
        /** @return tổng tiền hoàn đã hoàn tất; chỉ có ở truy vấn theo danh sách booking */
        Double getRefunds();
        /** @return tổng tiền hoàn đang chờ hoặc đang xử lý; chỉ có ở truy vấn theo danh sách booking */
        Double getPendingRefunds();
    }

    /** Dòng tiền gộp theo ngày: tiền thu và tiền hoàn của các booking được chọn. */
    interface DailyCashFlowFact {
        /** @return ngày phát sinh; {@code null} cho nhóm hoàn tiền chưa có ngày cập nhật */
        LocalDate getDate();
        /** @return tổng tiền thu trong ngày, làm tròn theo đồng */
        Long getGrossAmount();
        /** @return tổng tiền hoàn đã hoàn tất trong ngày */
        Long getRefundAmount();
        /** @return số lần hoàn tiền đã hoàn tất nhưng thiếu thời điểm cập nhật */
        Long getUndatedCompletedRefundCount();
    }

    /**
     * Tính dòng tiền theo ngày cho các booking đã chọn, mỗi booking chỉ lấy một thanh toán thành công
     * có thời điểm xác nhận sớm nhất. Tiền thu tính theo ngày xác nhận, tiền hoàn theo ngày cập nhật hoàn tiền.
     *
     * @param bookingIds danh sách mã booking
     * @param from mốc bắt đầu (bao gồm)
     * @param to mốc kết thúc (không bao gồm)
     * @return các dòng theo ngày, dòng không có ngày xếp cuối
     */
    @Query(value = "WITH confirmations AS (SELECT aggregate_id, MIN(created_at) AS confirmed_at FROM outbox_events " +
            "WHERE aggregate_type='PAYMENT' AND event_type='PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "selected AS (SELECT DISTINCT ON (p.booking_id) p.booking_id,p.id AS payment_id,p.amount,confirmations.confirmed_at " +
            "FROM payments p JOIN confirmations ON confirmations.aggregate_id=p.id " +
            "WHERE p.booking_id IN (:bookingIds) AND p.status IN ('SUCCESS','REFUNDED') " +
            "ORDER BY p.booking_id,confirmations.confirmed_at,p.id), " +
            "cashflow AS (" +
            "SELECT CAST(s.confirmed_at AS DATE) AS day,ROUND(SUM(COALESCE(s.amount,0)))::BIGINT AS gross_amount, " +
            "0::BIGINT AS refund_amount,0::BIGINT AS undated_refund_count FROM selected s " +
            "WHERE s.confirmed_at >= :from AND s.confirmed_at < :to GROUP BY CAST(s.confirmed_at AS DATE) " +
            "UNION ALL SELECT CAST(r.updated_at AS DATE),0::BIGINT,ROUND(SUM(COALESCE(r.amount,0)))::BIGINT,0::BIGINT " +
            "FROM refunds r JOIN selected s ON s.payment_id=r.payment_id WHERE r.status='COMPLETED' AND r.updated_at >= :from " +
            "AND r.updated_at < :to GROUP BY CAST(r.updated_at AS DATE) " +
            "UNION ALL SELECT NULL::DATE,0::BIGINT,0::BIGINT,COUNT(*)::BIGINT FROM refunds r JOIN selected s " +
            "ON s.payment_id=r.payment_id WHERE r.status='COMPLETED' AND r.updated_at IS NULL) " +
            "SELECT day AS date,SUM(gross_amount)::BIGINT AS grossAmount,SUM(refund_amount)::BIGINT AS refundAmount, " +
            "SUM(undated_refund_count)::BIGINT AS undatedCompletedRefundCount FROM cashflow GROUP BY day ORDER BY day NULLS LAST",
            nativeQuery = true)
    List<DailyCashFlowFact> findDailyCashFlowFactsByBookingIds(@Param("bookingIds") List<String> bookingIds,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Liệt kê các thanh toán thành công trong khoảng thời gian xác nhận, mỗi booking một dòng
     * (thanh toán xác nhận sớm nhất), sắp theo thời điểm xác nhận.
     *
     * @param from mốc bắt đầu (bao gồm)
     * @param to mốc kết thúc (không bao gồm)
     * @param limit số dòng tối đa
     * @return danh sách thanh toán, chưa kèm số liệu hoàn tiền
     */
    @Query(value = "WITH confirmations AS (SELECT aggregate_id, MIN(created_at) AS confirmed_at FROM outbox_events " +
            "WHERE aggregate_type = 'PAYMENT' AND event_type = 'PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "earliest_by_booking AS (SELECT DISTINCT ON (p.booking_id) p.booking_id, p.id AS payment_id, " +
            "confirmations.confirmed_at, p.amount FROM payments p LEFT JOIN confirmations ON confirmations.aggregate_id = p.id " +
            "WHERE p.status IN ('SUCCESS', 'REFUNDED') ORDER BY p.booking_id, confirmations.confirmed_at ASC NULLS LAST, p.id) " +
            "SELECT booking_id AS bookingId, payment_id AS paymentId, confirmed_at AS confirmedAt, amount AS revenue " +
            "FROM earliest_by_booking WHERE confirmed_at >= :from AND confirmed_at < :to " +
            "ORDER BY confirmed_at, booking_id LIMIT :limit", nativeQuery = true)
    List<SuccessfulPaymentFact> findSuccessfulPaymentFactsInRange(@Param("from") LocalDateTime from,
                                                                   @Param("to") LocalDateTime to,
                                                                   @Param("limit") int limit);

    /**
     * Lấy thanh toán thành công của các booking đã chọn trong khoảng thời gian xác nhận, kèm tổng tiền
     * hoàn đã hoàn tất và tổng tiền hoàn đang chờ xử lý.
     *
     * @param bookingIds danh sách mã booking
     * @param from mốc bắt đầu (bao gồm)
     * @param to mốc kết thúc (không bao gồm)
     * @return danh sách thanh toán kèm số liệu hoàn tiền
     */
    @Query(value = "WITH confirmations AS (SELECT aggregate_id, MIN(created_at) AS confirmed_at FROM outbox_events " +
            "WHERE aggregate_type='PAYMENT' AND event_type='PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "selected AS (SELECT DISTINCT ON (p.booking_id) p.booking_id,p.id AS payment_id,p.amount,confirmations.confirmed_at " +
            "FROM payments p JOIN confirmations ON confirmations.aggregate_id=p.id " +
            "WHERE p.booking_id IN (:bookingIds) AND p.status IN ('SUCCESS','REFUNDED') " +
            "AND confirmations.confirmed_at >= :from AND confirmations.confirmed_at < :to " +
            "ORDER BY p.booking_id, confirmations.confirmed_at, p.id) " +
            "SELECT s.booking_id AS bookingId,s.payment_id AS paymentId,s.confirmed_at AS confirmedAt,s.amount AS revenue, " +
            "COALESCE(SUM(r.amount) FILTER (WHERE r.status='COMPLETED'),0) AS refunds, " +
            "COALESCE(SUM(r.amount) FILTER (WHERE r.status IN ('PENDING','PROCESSING')),0) AS pendingRefunds " +
            "FROM selected s LEFT JOIN refunds r ON r.payment_id=s.payment_id GROUP BY s.booking_id,s.payment_id,s.confirmed_at,s.amount",
            nativeQuery = true)
    List<SuccessfulPaymentFact> findSuccessfulPaymentFactsByBookingIds(@Param("bookingIds") List<String> bookingIds,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Đếm số booking đã thanh toán thành công (hoặc đã hoàn tiền) nhưng không tìm thấy sự kiện
     * {@code PAYMENT_COMPLETED} để suy ra thời điểm xác nhận. Dùng để phát hiện dữ liệu thiếu.
     *
     * @return số booking thiếu mốc xác nhận
     */
    @Query(value = "WITH confirmations AS (SELECT aggregate_id, MIN(created_at) AS confirmed_at FROM outbox_events " +
            "WHERE aggregate_type = 'PAYMENT' AND event_type = 'PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "earliest_by_booking AS (SELECT DISTINCT ON (p.booking_id) p.booking_id, confirmations.confirmed_at " +
            "FROM payments p LEFT JOIN confirmations ON confirmations.aggregate_id = p.id WHERE p.status IN ('SUCCESS', 'REFUNDED') " +
            "ORDER BY p.booking_id, confirmations.confirmed_at ASC NULLS LAST, p.id) " +
            "SELECT COUNT(*) FROM earliest_by_booking WHERE confirmed_at IS NULL", nativeQuery = true)
    long countBookingsMissingConfirmationTimestamp();
}
