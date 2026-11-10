package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, String> {
    List<TicketType> findByEventId(String eventId);
    List<TicketType> findByShowtimeId(String showtimeId);
    List<TicketType> findByEventIdAndShowtimeId(String eventId, String showtimeId);

    @Modifying
    @Transactional
    void deleteByEventId(String eventId);

    @Modifying
    @Query("UPDATE TicketType t SET t.totalQuantity = t.totalQuantity - 1, t.version = t.version + 1 WHERE t.id = :id AND t.totalQuantity > 0")
    int decrementTotalQuantityAtomic(@Param("id") String id);

    /**
     * A row from before initial_quantity existed has only the (already decremented) remaining stock. The organiser sync computes
     * sold = initial - total, which is 0 for such a row, so the first edit used to reset the stock to the new number and forget
     * every ticket already sold. This writes initial = remaining + issued first, so sold is right afterwards.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE ticket_types SET initial_quantity = total_quantity + (SELECT COUNT(*) FROM tickets k WHERE k.ticket_type_id = ticket_types.id "
            + "AND k.status <> 'REVOKED' AND (k.acquisition_type IS NULL OR k.acquisition_type = 'PRIMARY')), version = version + 1 "
            + "WHERE id = :id AND initial_quantity IS NULL AND total_quantity IS NOT NULL", nativeQuery = true)
    int backfillInitialQuantityAtomic(@Param("id") String id);

    /** Admin stock repair: remaining := value, only when it still covers the held quantity. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE TicketType t SET t.totalQuantity = :total, t.version = t.version + 1 "
            + "WHERE t.id = :id AND :total >= 0 AND COALESCE(t.reservedQuantity, 0) <= :total")
    int setTotalQuantityAtomic(@Param("id") String id, @Param("total") int total);

    /** Holds stock only while total - reserved still covers the quantity; returns 0 rows when it does not. */
    @Modifying
    @Query("UPDATE TicketType t SET t.reservedQuantity = COALESCE(t.reservedQuantity, 0) + :qty, t.version = t.version + 1 "
            + "WHERE t.id = :id AND (t.ticketTypeStatus IS NULL OR t.ticketTypeStatus <> com.haloticket.ticket_service.enums.TicketTypeStatus.HIDDEN) "
            + "AND COALESCE(t.totalQuantity, 0) - COALESCE(t.reservedQuantity, 0) >= :qty")
    int reserveAtomic(@Param("id") String id, @Param("qty") int qty);

    @Modifying
    @Query("UPDATE TicketType t SET t.reservedQuantity = CASE WHEN COALESCE(t.reservedQuantity, 0) > :qty THEN COALESCE(t.reservedQuantity, 0) - :qty ELSE 0 END, "
            + "t.version = t.version + 1 WHERE t.id = :id")
    int releaseReservedAtomic(@Param("id") String id, @Param("qty") int qty);

    /**
     * Applies the organizer's issued number in one statement, so no read-modify-write window exists against
     * reserveAtomic / decrementTotalQuantityAtomic. sold = initial - total (0 for old rows with a null column);
     * the new remaining stock is newInitial - sold and must still cover what unpaid bookings hold. Also writes the
     * editable attributes; status is deliberately not touched. Returns 0 rows when the new number is too low.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketType t SET "
            + "t.totalQuantity = :newInitial - (CASE WHEN t.initialQuantity IS NULL OR t.totalQuantity IS NULL THEN 0 ELSE t.initialQuantity - t.totalQuantity END), "
            + "t.initialQuantity = :newInitial, t.name = :name, t.price = :price, t.isFree = :isFree, "
            + "t.minPerOrder = :minPerOrder, t.maxPerOrder = :maxPerOrder, t.description = :description, "
            + "t.updatedAt = :now, t.version = t.version + 1 "
            + "WHERE t.id = :id AND :newInitial - (CASE WHEN t.initialQuantity IS NULL OR t.totalQuantity IS NULL THEN 0 ELSE t.initialQuantity - t.totalQuantity END) >= COALESCE(t.reservedQuantity, 0)")
    int applyIssuedQuantityAtomic(@Param("id") String id, @Param("newInitial") int newInitial, @Param("name") String name,
            @Param("price") Double price, @Param("isFree") Boolean isFree, @Param("minPerOrder") Integer minPerOrder,
            @Param("maxPerOrder") Integer maxPerOrder, @Param("description") String description,
            @Param("now") LocalDateTime now);

    /** Same attributes without touching quantities, for a sync request that carries no number. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketType t SET t.name = :name, t.price = :price, t.isFree = :isFree, t.minPerOrder = :minPerOrder, "
            + "t.maxPerOrder = :maxPerOrder, t.description = :description, t.updatedAt = :now, t.version = t.version + 1 WHERE t.id = :id")
    int applyDetailsAtomic(@Param("id") String id, @Param("name") String name, @Param("price") Double price,
            @Param("isFree") Boolean isFree, @Param("minPerOrder") Integer minPerOrder,
            @Param("maxPerOrder") Integer maxPerOrder, @Param("description") String description,
            @Param("now") LocalDateTime now);

    /**
     * Only the ticket types with a discrepancy or a note, same logic as scripts/audit/ticket_db_stock_reconciliation.sql
     * part A. Columns: id, event_id, showtime_id, name, initial, remaining, held, issued, held_by_reservations.
     */
    @Query(value = "WITH issued AS (SELECT t.ticket_type_id AS tid, COUNT(*) AS n FROM tickets t "
            + "WHERE t.status <> 'REVOKED' AND (t.acquisition_type IS NULL OR t.acquisition_type = 'PRIMARY') GROUP BY t.ticket_type_id), "
            + "held AS (SELECT r.ticket_type_id AS tid, COALESCE(SUM(r.quantity), 0) AS n FROM ticket_stock_reservations r "
            + "WHERE r.status = 'HELD' GROUP BY r.ticket_type_id) "
            + "SELECT x.id, x.event_id, x.showtime_id, x.name, x.initial_quantity, x.total_quantity, x.held, x.issued, x.held_res FROM ("
            + "SELECT tt.id AS id, tt.event_id AS event_id, tt.showtime_id AS showtime_id, tt.name AS name, "
            + "tt.initial_quantity AS initial_quantity, tt.total_quantity AS total_quantity, COALESCE(tt.reserved_quantity, 0) AS held, "
            + "COALESCE(i.n, 0) AS issued, COALESCE(h.n, 0) AS held_res "
            + "FROM ticket_types tt LEFT JOIN issued i ON i.tid = tt.id LEFT JOIN held h ON h.tid = tt.id) x "
            + "WHERE x.held <> x.held_res "
            + "OR (x.initial_quantity IS NULL AND x.issued > 0) OR x.total_quantity IS NULL "
            + "OR x.total_quantity < 0 OR x.held > x.total_quantity OR x.total_quantity > x.initial_quantity "
            + "OR GREATEST(0, x.initial_quantity - x.total_quantity) <> x.issued "
            + "ORDER BY x.event_id, x.name, x.id LIMIT :limit", nativeQuery = true)
    List<Object[]> findStockDiscrepancies(@Param("limit") int limit);
}
