package com.haloticket.ticket_service.repository;

import com.haloticket.ticket_service.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
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
}
