package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.*;
import com.haloticket.marketplace_service.dto.CreateGiftRequest;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizerTransferLifecycleTest {
    final TicketListingRepository listings = mock(TicketListingRepository.class);
    final MarketplaceSagaRepository sagas = mock(MarketplaceSagaRepository.class);
    final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    final TicketTransferRepository gifts = mock(TicketTransferRepository.class);
    final TicketServiceClient tickets = mock(TicketServiceClient.class);
    final EventServiceClient events = mock(EventServiceClient.class);
    final UserServiceClient users = mock(UserServiceClient.class);
    final EventTransferPolicyService gate = mock(EventTransferPolicyService.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final TicketListingService service = new TicketListingService(listings, sagas, outbox, gifts, mock(MarketplaceTransactionRepository.class), tickets, events, users, mapper, gate);
    TicketListing listing(ListingStatus status) {
        var item = TicketListing.builder().id("listing").ticketId("t1").sellerId("seller").eventId("event").price(BigDecimal.TEN).status(status).sagaId("saga").build();
        when(listings.findByIdForUpdate("listing")).thenReturn(Optional.of(item));
        when(sagas.findById("saga")).thenReturn(Optional.of(MarketplaceSaga.builder().id("saga").type("RESALE_LISTING").state(SagaState.COMPLETED).ticketId("t1").payload("{}").build()));
        return item;
    }
    @Test void approvalPublishesOnceAndCrossEventIsDenied() {
        var item = listing(ListingStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> service.moderateListingAsOrganizer("owner", "other", "listing", true, null)).isInstanceOf(ResponseStatusException.class);
        assertThat(item.getStatus()).isEqualTo(ListingStatus.PENDING_APPROVAL);
        service.moderateListingAsOrganizer("owner", "event", "listing", true, null);
        service.moderateListingAsOrganizer("owner", "event", "listing", true, null);
        assertThat(item.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(item.getPublishedAt()).isNotNull();
        verify(outbox, times(1)).save(any());
    }
    @Test void rejectionNeedsReasonAndReleasesAllUnsoldTicketsOnce() {
        var item = listing(ListingStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> service.moderateListingAsOrganizer("owner", "event", "listing", false, " ")).isInstanceOf(ResponseStatusException.class);
        service.moderateListingAsOrganizer("owner", "event", "listing", false, "Sai thông tin");
        service.cancelListingAsOrganizer("owner", "event", "listing", null);
        assertThat(item.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(item.getModerationReason()).isEqualTo("Sai thông tin");
        verify(outbox, times(1)).save(argThat(event -> event.getTopic().equals("haloticket.ticket.release.requested.v1") && event.getPayload().contains("t1")));
    }
    @Test void cannotDelistAReservedListing() {
        listing(ListingStatus.RESERVED);
        assertThatThrownBy(() -> service.cancelListingAsOrganizer("owner", "event", "listing", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("LISTING_NOT_CANCELLABLE");
        verifyNoInteractions(outbox);
    }
    @Test void pendingModerationIsNotPublishedAndPinsTheConfiguredFee() throws Exception {
        var saga = MarketplaceSaga.builder().id("new").type("RESALE_LISTING").ticketId("t1").state(SagaState.WAITING_LOCK)
                .payload("{\"ticketId\":\"t1\",\"sellerId\":\"seller\",\"eventId\":\"event\",\"price\":100000,\"requireApproval\":true,\"serviceFeePercent\":7.5}").build();
        when(sagas.findById("new")).thenReturn(Optional.of(saga));
        when(listings.save(any())).thenAnswer(inv -> { TicketListing listing = inv.getArgument(0); listing.setId("new-listing"); return listing; });
        service.activateFromTicketLock("new");
        var captor = org.mockito.ArgumentCaptor.forClass(TicketListing.class);
        verify(listings).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ListingStatus.PENDING_APPROVAL);
        assertThat(captor.getValue().getPublishedAt()).isNull();
        assertThat(captor.getValue().getServiceFeePercent()).isEqualByComparingTo("7.5");
        verify(outbox).save(argThat(event -> event.getTopic().equals("haloticket.marketplace.listing.pending-approval.v1")));
    }
    @Test void organizerCannotCancelAGiftAlreadyTransferring() {
        var gift = TicketTransfer.builder().sagaId("gift").ticketId("t1").eventId("event").status(TransferStatus.PENDING).build();
        var saga = MarketplaceSaga.builder().id("gift").type("GIFT_TRANSFER").state(SagaState.WAITING_TRANSFER).build();
        when(gifts.findBySagaIdForUpdate("gift")).thenReturn(Optional.of(gift));
        when(sagas.findByIdForUpdate("gift")).thenReturn(Optional.of(saga));
        assertThatThrownBy(() -> service.cancelGiftAsOrganizer("owner", "event", "gift", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("GIFT_NOT_CANCELLABLE");
        verifyNoInteractions(outbox);
    }
    @Test void pendingGiftCancellationPreventsLaterAcceptance() {
        var gift = TicketTransfer.builder().sagaId("gift").ticketId("t1").eventId("event").fromUserId("sender").toUserId("recipient").recipientEmail("recipient@example.com").status(TransferStatus.PENDING).build();
        var saga = MarketplaceSaga.builder().id("gift").type("GIFT_TRANSFER").ticketId("t1").state(SagaState.WAITING_RECIPIENT).payload("{}").build();
        when(gifts.findBySagaIdForUpdate("gift")).thenReturn(Optional.of(gift));
        when(sagas.findByIdForUpdate("gift")).thenReturn(Optional.of(saga));
        service.cancelGiftAsOrganizer("owner", "event", "gift", null);
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(gift.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer recipient", "gift", null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("GIFT_INVITATION_INVALID");
        verify(outbox, times(1)).save(any());
    }
    // acceptGift bắt buộc có token lời mời khớp invitationTokenHash (SHA-256 dạng hex), nên test phải cấp token hợp lệ để chạm tới bước eKYC
    static final String INVITE_TOKEN = "invite-token";
    static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    @Test void recipientMustPassEkycBeforeAcceptingAGift() {
        var gift = TicketTransfer.builder().sagaId("gift").ticketId("t1").eventId("event").fromUserId("sender").toUserId("recipient").recipientEmail("recipient@example.com").invitationTokenHash(sha256(INVITE_TOKEN)).requireEkyc(true).status(TransferStatus.PENDING).invitationExpiresAt(Instant.now().plusSeconds(3600)).build();
        var saga = MarketplaceSaga.builder().id("gift").type("GIFT_TRANSFER").ticketId("t1").state(SagaState.WAITING_RECIPIENT).payload("{}").build();
        when(gifts.findBySagaIdForUpdate("gift")).thenReturn(Optional.of(gift));
        when(sagas.findByIdForUpdate("gift")).thenReturn(Optional.of(saga));
        when(users.getCurrentUser(anyString())).thenReturn(new UserServiceClient.CurrentUser("recipient", "recipient@example.com", "Recipient"));
        when(users.getResaleEligibility(anyString())).thenReturn(new UserServiceClient.ResaleSellerEligibility(false, "recipient", null, "NOT_VERIFIED"));
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer recipient", "gift", INVITE_TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("EKYC_REQUIRED");
        assertThat(saga.getState()).isEqualTo(SagaState.WAITING_RECIPIENT);
        verifyNoInteractions(outbox);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "GIFT_DISABLED")).when(gate).requireGiftEnabled("event");
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer recipient", "gift", INVITE_TOKEN)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("GIFT_DISABLED");
        doNothing().when(gate).requireGiftEnabled("event");
        when(users.getResaleEligibility(anyString())).thenReturn(new UserServiceClient.ResaleSellerEligibility(true, "recipient", 1L, null));
        service.acceptGift("recipient", "Bearer recipient", "gift", INVITE_TOKEN);
        assertThat(gift.getRecipientEkycVerified()).isTrue();
        verify(outbox).save(argThat(event -> event.getPayload().contains("\"maxGiftCount\":3")));
    }
    @Test void giftLimitIsCheckedBeforeCreatingAnInvitationAndCustomTtlIsHonored() throws Exception {
        when(users.getGiftRecipient(anyString(), anyString())).thenReturn(new UserServiceClient.GiftRecipient("recipient", "r@example.com", "R"));
        when(tickets.getResaleEligibility(anyString(), eq("t1"))).thenReturn(new TicketServiceClient.ResaleEligibilityResponse("t1", "event", true, null, BigDecimal.TEN, null, null, null, null, null, "VIP", null, null, null, "type", "show", 3, null));
        when(events.getGiftPolicy(anyString(), eq("event"))).thenReturn(new EventServiceClient.GiftPolicyResponse(true, null, null, 48, true, 3, "ALL", Set.of(), 1L));
        assertThatThrownBy(() -> service.startGift("sender", "Bearer sender", "key", new CreateGiftRequest("t1", "r@example.com", null, true))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("GIFT_LIMIT_REACHED");
        verifyNoInteractions(outbox);
        when(events.getGiftPolicy(anyString(), eq("event"))).thenReturn(new EventServiceClient.GiftPolicyResponse(true, null, null, 48, false, 0, "ALL", Set.of(), 2L));
        var result = service.startGift("sender", "Bearer sender", "key", new CreateGiftRequest("t1", "r@example.com", null, true));
        var captor = org.mockito.ArgumentCaptor.forClass(MarketplaceSaga.class);
        verify(sagas, atLeastOnce()).save(captor.capture());
        var saga = captor.getValue();
        when(sagas.findById(result.sagaId())).thenReturn(Optional.of(saga));
        var transferCaptor = org.mockito.ArgumentCaptor.forClass(TicketTransfer.class);
        verify(gifts).save(transferCaptor.capture());
        var transfer = transferCaptor.getValue();
        when(gifts.findBySagaId(result.sagaId())).thenReturn(Optional.of(transfer));
        service.activateFromTicketLock(result.sagaId());
        assertThat(transfer.isRequireEkyc()).isFalse();
        assertThat(transfer.getInvitationExpiresAt()).isBetween(Instant.now().plusSeconds(47 * 3600), Instant.now().plusSeconds(49 * 3600));
    }
}
