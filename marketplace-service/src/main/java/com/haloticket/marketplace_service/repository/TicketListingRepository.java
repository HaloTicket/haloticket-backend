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
public interface TicketListingRepository extends JpaRepository<TicketListing,String> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from TicketListing l where l.id = :id")
    Optional<TicketListing> findByIdForUpdate(@Param("id") String id);
    @Query("select l from TicketListing l where l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now)")
    Page<TicketListing> findPublicActive(@Param("status") ListingStatus status, @Param("now") java.time.Instant now, Pageable pageable);
    Page<TicketListing> findBySellerId(String sellerId, Pageable pageable);
    Optional<TicketListing> findByTicketIdAndStatus(String ticketId, ListingStatus status);
    @Query("select l from TicketListing l where l.eventId = :eventId and l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now)")
    Page<TicketListing> findPublicActiveByEventId(@Param("eventId") String eventId, @Param("status") ListingStatus status,
                                                   @Param("now") java.time.Instant now, Pageable pageable);
    @Query("select new com.haloticket.marketplace_service.dto.ResaleEventSummary(l.eventId, count(l), min(l.price)) " +
            "from TicketListing l where l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now) " +
            "group by l.eventId order by min(l.price) asc")
    List<ResaleEventSummary> summarizePublicEvents(@Param("status") ListingStatus status, @Param("now") java.time.Instant now);
    List<TicketListing> findByStatusAndReservedUntilBefore(ListingStatus status, java.time.Instant now);
    Page<TicketListing> findByEventId(String eventId, Pageable pageable);
    Page<TicketListing> findByEventIdAndStatus(String eventId, ListingStatus status, Pageable pageable);
    long countByEventIdAndStatus(String eventId, ListingStatus status);
    long countByStatus(ListingStatus status);
    @Query("select min(l.createdAt) from TicketListing l where l.status = :status")
    java.time.Instant oldestCreatedAtByStatus(@Param("status") ListingStatus status);
    List<TicketListing> findByEventIdAndStatus(String eventId, ListingStatus status);
}
