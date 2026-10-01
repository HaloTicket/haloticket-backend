package com.haloticket.payment_service.repository;

import com.haloticket.payment_service.entity.OutboxEvent;
import com.haloticket.payment_service.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    interface SuccessfulPaymentFact {
        String getBookingId();
        String getPaymentId();
        LocalDateTime getConfirmedAt();
        Double getRevenue();
    }

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
            "WHERE aggregate_type = 'PAYMENT' AND event_type = 'PAYMENT_COMPLETED' GROUP BY aggregate_id), " +
            "earliest_by_booking AS (SELECT DISTINCT ON (p.booking_id) p.booking_id, confirmations.confirmed_at " +
            "FROM payments p LEFT JOIN confirmations ON confirmations.aggregate_id = p.id WHERE p.status IN ('SUCCESS', 'REFUNDED') " +
            "ORDER BY p.booking_id, confirmations.confirmed_at ASC NULLS LAST, p.id) " +
            "SELECT COUNT(*) FROM earliest_by_booking WHERE confirmed_at IS NULL", nativeQuery = true)
    long countBookingsMissingConfirmationTimestamp();
}
