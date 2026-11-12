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

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    interface SuccessfulPaymentFact {
        String getBookingId();
        String getPaymentId();
        LocalDateTime getConfirmedAt();
        Double getRevenue();
        Double getRefunds();
        Double getPendingRefunds();
    }

    interface DailyCashFlowFact {
        LocalDate getDate();
        Long getGrossAmount();
        Long getRefundAmount();
        Long getUndatedCompletedRefundCount();
    }

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

    @Query(value = "WITH confirmations AS (SELECT aggregate_id, MIN(created_at) AS confirmed_at FROM outbox_events " +
            "WHERE aggregate_type = 'PAYMENT' AND event_type = 'PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "earliest_by_booking AS (SELECT DISTINCT ON (p.booking_id) p.booking_id, confirmations.confirmed_at " +
            "FROM payments p LEFT JOIN confirmations ON confirmations.aggregate_id = p.id WHERE p.status IN ('SUCCESS', 'REFUNDED') " +
            "ORDER BY p.booking_id, confirmations.confirmed_at ASC NULLS LAST, p.id) " +
            "SELECT COUNT(*) FROM earliest_by_booking WHERE confirmed_at IS NULL", nativeQuery = true)
    long countBookingsMissingConfirmationTimestamp();
}
