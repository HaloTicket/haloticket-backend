package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface TicketListingRepository extends JpaRepository<TicketListing,String> {
    @Query("select l from TicketListing l where l.status = :status and l.publishedAt is not null and (l.expiresAt is null or l.expiresAt > :now)")
    Page<TicketListing> findPublicActive(@Param("status") ListingStatus status, @Param("now") java.time.Instant now, Pageable pageable);
    Page<TicketListing> findBySellerId(String sellerId, Pageable pageable);
    Optional<TicketListing> findByTicketIdAndStatus(String ticketId, ListingStatus status);
}
