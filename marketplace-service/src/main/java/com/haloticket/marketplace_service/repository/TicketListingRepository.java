package com.haloticket.marketplace_service.repository;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface TicketListingRepository extends JpaRepository<TicketListing,String> {
    Page<TicketListing> findByStatusAndPublishedAtIsNotNullAndExpiresAtAfter(ListingStatus status, java.time.Instant now, Pageable pageable);
    Page<TicketListing> findBySellerId(String sellerId, Pageable pageable);
    Optional<TicketListing> findByTicketIdAndStatus(String ticketId, ListingStatus status);
}
