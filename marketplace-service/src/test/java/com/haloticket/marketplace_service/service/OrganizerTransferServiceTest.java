package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.authz.EventAccessClient;
import com.haloticket.marketplace_service.authz.EventAccessView;
import com.haloticket.marketplace_service.authz.EventScopeProperties;
import com.haloticket.marketplace_service.client.EventServiceClient;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.entity.TicketListing;
import com.haloticket.marketplace_service.entity.TicketTransfer;
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
import java.util.Optional;

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
    private TicketListingService ticketListingService;

    @BeforeEach
    void setUp() {
        eventServiceClient = mock(EventServiceClient.class);
        listingRepository = mock(TicketListingRepository.class);
        transferRepository = mock(TicketTransferRepository.class);
        policyGate = mock(EventTransferPolicyService.class);
        ticketListingService = mock(TicketListingService.class);
        service = new OrganizerTransferService(listingRepository, transferRepository, policyGate, ticketListingService,
                new EventAccessClient(eventServiceClient, new EventScopeProperties()));
    }

    @Test
    void requireOrganizerAccess_allowsMarketplaceViewers() {
        grant("Bearer owner", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");
        grant("Bearer manager", "MARKETPLACE_VIEW");

        assertThatCode(() -> service.requireOrganizerAccess("Bearer owner", "evt-1")).doesNotThrowAnyException();
        assertThatCode(() -> service.requireOrganizerAccess("Bearer manager", "evt-1")).doesNotThrowAnyException();
    }

    @Test
    void requireOrganizerAccess_deniesCheckinStaffAndOtherEvents() {
        grant("Bearer staff", "CHECKIN_SCAN");
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer staff", "evt-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        // an owner of evt-1 is refused by event-service for evt-2
        when(eventServiceClient.getMyAccess("evt-2", "Bearer owner")).thenThrow(forbidden());
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer owner", "evt-2"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requireOrganizerAccess_throwsUnauthorizedWhenTokenMissing() {
        assertThatThrownBy(() -> service.requireOrganizerAccess("", "evt-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void getListings_returnsMappedOrganizerPage() {
        grant("Bearer tok", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");

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
        assertThat(item.serviceFeePercent()).isEqualByComparingTo("5.00");
    }

    @Test
    void ownerAndAdminCanDelegateOrganizerActionsWithAuthenticatedActor() {
        grant("Bearer tok", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(TicketListing.builder().eventId("evt-1").build()));
        when(transferRepository.findBySagaId("gift-1")).thenReturn(Optional.of(TicketTransfer.builder().eventId("evt-1").build()));

        service.moderateListing("Bearer tok", "actor-1", "evt-1", "listing-1", false, "Wrong price");
        service.cancelListing("Bearer tok", "actor-1", "evt-1", "listing-1", "Delisted");
        service.cancelGift("Bearer tok", "actor-1", "evt-1", "gift-1", "Cancelled");

        verify(ticketListingService).moderateListingAsOrganizer("actor-1", "evt-1", "listing-1", false, "Wrong price");
        verify(ticketListingService).cancelListingAsOrganizer("actor-1", "evt-1", "listing-1", "Delisted");
        verify(ticketListingService).cancelGiftAsOrganizer("actor-1", "evt-1", "gift-1", "Cancelled");
    }

    @Test
    void managerHasReadAccessButCannotMutateAnyTransfer() {
        grant("Bearer tok", "MARKETPLACE_VIEW");
        service.requireOrganizerAccess("Bearer tok", "evt-1");

        assertThatThrownBy(() -> service.moderateListing("Bearer tok", "actor", "evt-1", "listing", true, null))
                .isInstanceOf(ResponseStatusException.class).satisfies(error ->
                        assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.cancelListing("Bearer tok", "actor", "evt-1", "listing", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.cancelGift("Bearer tok", "actor", "evt-1", "gift", null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ticketListingService, listingRepository, transferRepository);
    }

    @Test
    void organizerCannotMutateRecordsFromAnotherEvent() {
        grant("Bearer tok", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");
        when(listingRepository.findById("listing")).thenReturn(Optional.of(TicketListing.builder().eventId("evt-2").build()));
        when(transferRepository.findBySagaId("gift")).thenReturn(Optional.of(TicketTransfer.builder().eventId("evt-2").build()));

        assertThatThrownBy(() -> service.moderateListing("Bearer tok", "actor", "evt-1", "listing", true, null))
                .isInstanceOf(ResponseStatusException.class).satisfies(error ->
                        assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.cancelListing("Bearer tok", "actor", "evt-1", "listing", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.cancelGift("Bearer tok", "actor", "evt-1", "gift", null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ticketListingService);
    }

    @Test
    void giftMetadataUsesActualTransferTimeWithoutInventingRecipientVerification() {
        Instant receivedAt = Instant.parse("2026-10-15T00:00:00Z");
        grant("Bearer tok", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");
        when(transferRepository.findBySagaId("gift")).thenReturn(Optional.of(TicketTransfer.builder()
                .sagaId("gift").ticketId("ticket").eventId("evt-1").status(com.haloticket.marketplace_service.enums.TransferStatus.COMPLETED)
                .requireEkyc(true).transferredAt(receivedAt).build()));

        var response = service.getGiftDetail("Bearer tok", "evt-1", "gift");

        assertThat(response.receivedAt()).isEqualTo(receivedAt);
        assertThat(response.requireEkyc()).isTrue();
        assertThat(response.isRecipientEkycVerified()).isNull();
    }

    @Test
    void giftReadModelsExposeVerificationRecordedDuringAcceptance() {
        grant("Bearer tok", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE");
        TicketTransfer transfer = TicketTransfer.builder().sagaId("gift").ticketId("ticket").eventId("evt-1")
                .status(com.haloticket.marketplace_service.enums.TransferStatus.COMPLETED).recipientEkycVerified(true).build();
        when(transferRepository.findBySagaId("gift")).thenReturn(Optional.of(transfer));
        when(transferRepository.findByEventId("evt-1", PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(transfer)));

        assertThat(service.getGiftDetail("Bearer tok", "evt-1", "gift").isRecipientEkycVerified()).isTrue();
        assertThat(service.getGifts("Bearer tok", "evt-1", PageRequest.of(0, 20), null).getContent().get(0).isRecipientEkycVerified()).isTrue();
    }

    private void grant(String token, String... permissions) {
        when(eventServiceClient.getMyAccess("evt-1", token)).thenReturn(access("MEMBER", permissions));
    }

    private static feign.FeignException.Forbidden forbidden() {
        return new feign.FeignException.Forbidden("no", feign.Request.create(feign.Request.HttpMethod.GET, "/x", java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null), new byte[0], java.util.Map.of());
    }

    private static EventAccessView access(String role, String... permissions) {
        return new EventAccessView("evt-1", "u-1", false, role, "as-1", List.of(permissions));
    }

    @Test
    void readsNeedMarketplaceViewAndChangesNeedModerate() {
        when(eventServiceClient.getMyAccess("evt-1", "Bearer manager")).thenReturn(access("MANAGER", "MARKETPLACE_VIEW"));
        when(eventServiceClient.getMyAccess("evt-1", "Bearer admin")).thenReturn(access("ADMIN", "MARKETPLACE_VIEW", "MARKETPLACE_MODERATE"));
        when(eventServiceClient.getMyAccess("evt-1", "Bearer checkin")).thenReturn(access("CHECKIN_STAFF", "CHECKIN_SCAN"));

        assertThatCode(() -> service.requireOrganizerAccess("Bearer manager", "evt-1")).doesNotThrowAnyException();
        assertThatCode(() -> service.requireOrganizerAccess("Bearer admin", "evt-1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer checkin", "evt-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        // a manager may look but not moderate
        assertThatThrownBy(() -> service.cancelListing("Bearer manager", "actor", "evt-1", "listing", "why"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aStrangerAndAnEventServiceOutageAreRefused() {
        when(eventServiceClient.getMyAccess("evt-1", "Bearer stranger"))
                .thenThrow(new feign.FeignException.Forbidden("no", feign.Request.create(feign.Request.HttpMethod.GET, "/x", java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null), new byte[0], java.util.Map.of()));
        when(eventServiceClient.getMyAccess("evt-1", "Bearer down")).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer stranger", "evt-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.requireOrganizerAccess("Bearer down", "evt-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.requireOrganizerAccess(null, "evt-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
