package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.repository.TicketListingRepository;
import com.haloticket.marketplace_service.repository.TicketTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrganizerTransferServiceTest {

    private EventServiceClient eventServiceClient;
    private TicketListingRepository listingRepository;
    private TicketTransferRepository transferRepository;
    private EventTransferPolicyService policyGate;
    private OrganizerTransferService service;

    @BeforeEach
    void setUp() {
        eventServiceClient = mock(EventServiceClient.class);
        listingRepository = mock(TicketListingRepository.class);
        transferRepository = mock(TicketTransferRepository.class);
        policyGate = mock(EventTransferPolicyService.class);
        service = new OrganizerTransferService(eventServiceClient, listingRepository, transferRepository, policyGate);
    }

    @Test
    void requireOrganizerAccess_allowsRawAndQuotedRoles() {
        // Quoted JSON from OpenFeign: "\"OWNER\""
        when(eventServiceClient.getMyRole("Bearer tok1", "evt-1")).thenReturn("\"OWNER\"");
        assertThatCode(() -> service.requireOrganizerAccess("Bearer tok1", "evt-1")).doesNotThrowAnyException();

        // Quoted MANAGER
        when(eventServiceClient.getMyRole("Bearer tok2", "evt-1")).thenReturn("\"MANAGER\"");
        assertThatCode(() -> service.requireOrganizerAccess("Bearer tok2", "evt-1")).doesNotThrowAnyException();

        // Raw OWNER
        when(eventServiceClient.getMyRole("Bearer tok3", "evt-1")).thenReturn("OWNER");
        assertThatCode(() -> service.requireOrganizerAccess("Bearer tok3", "evt-1")).doesNotThrowAnyException();

        // Raw ADMIN
        when(eventServiceClient.getMyRole("Bearer tok4", "evt-1")).thenReturn("ADMIN");
        assertThatCode(() -> service.requireOrganizerAccess("Bearer tok4", "evt-1")).doesNotThrowAnyException();
    }

    @Test
    void requireOrganizerAccess_deniesStaffOrInvalidRoles() {
        // STAFF should be forbidden
        when(eventServiceClient.getMyRole("Bearer tok", "evt-1")).thenReturn("\"STAFF\"");
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer tok", "evt-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // null role should be forbidden
        when(eventServiceClient.getMyRole("Bearer tok", "evt-2")).thenReturn(null);
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer tok", "evt-2"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requireOrganizerAccess_throwsUnauthorizedWhenTokenMissing() {
        assertThatThrownBy(() -> service.requireOrganizerAccess("", "evt-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getListings_returnsMappedOrganizerPage() {
        when(eventServiceClient.getMyRole("Bearer tok", "evt-1")).thenReturn("\"OWNER\"");

        TicketListing listing = TicketListing.builder()
                .id("list-12345678")
                .eventId("evt-1")
                .sellerId("seller@example.com")
                .ticketTypeNameSnapshot("VIP")
                .sectionSnapshot("A")
                .rowSnapshot("1")
                .seatSnapshot("05")
                .price(new BigDecimal("150000"))
                .status(ListingStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        Page<TicketListing> page = new PageImpl<>(List.of(listing));
        when(listingRepository.findByEventId("evt-1", PageRequest.of(0, 20))).thenReturn(page);

        Page<OrganizerListingResponse> res = service.getListings("Bearer tok", "evt-1", PageRequest.of(0, 20), null);
        assertThat(res.getContent()).hasSize(1);
        OrganizerListingResponse item = res.getContent().get(0);
        assertThat(item.listingId()).isEqualTo("list-12345678");
        assertThat(item.refCode()).isEqualTo("LST-LIST-123");
        assertThat(item.seatCode()).isEqualTo("A · Hàng 1 · Ghế 05");
        assertThat(item.price()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(item.status()).isEqualTo("ACTIVE");
    }
}
