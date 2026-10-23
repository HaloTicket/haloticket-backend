package com.haloticket.marketplace_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.marketplace_service.client.*;
import com.haloticket.marketplace_service.entity.*;
import com.haloticket.marketplace_service.enums.*;
import com.haloticket.marketplace_service.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Invitation token rules for acceptGift and giftInvitation: a missing hash is rejected like a bad token. */
class GiftInvitationTokenTest {

    private static final String SAGA_ID = "saga-token";
    private static final String TOKEN = "secret-token";

    private MarketplaceSagaRepository sagas;
    private TicketTransferRepository transfers;
    private OutboxEventRepository outbox;
    private UserServiceClient users;
    private TicketListingService service;

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        sagas = mock(MarketplaceSagaRepository.class);
        transfers = mock(TicketTransferRepository.class);
        outbox = mock(OutboxEventRepository.class);
        users = mock(UserServiceClient.class);
        service = new TicketListingService(
                mock(TicketListingRepository.class), sagas, outbox, transfers, mock(MarketplaceTransactionRepository.class),
                mock(TicketServiceClient.class), mock(EventServiceClient.class), users,
                new ObjectMapper().findAndRegisterModules(), mock(EventTransferPolicyService.class));
    }

    private void givenPendingGift(String tokenHash) {
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(SAGA_ID)
                .fromUserId("sender")
                .toUserId("recipient")
                .recipientEmail("recipient@test.com")
                .status(TransferStatus.PENDING)
                .invitationTokenHash(tokenHash)
                .invitationExpiresAt(Instant.now().plusSeconds(3600))
                .requireEkyc(false)
                .eventId("event-1")
                .ticketId("ticket-1")
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(SAGA_ID)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-1")
                .payload("{\"ticketId\":\"ticket-1\"}")
                .build();
        when(transfers.findBySagaIdForUpdate(SAGA_ID)).thenReturn(Optional.of(transfer));
        when(transfers.findBySagaId(SAGA_ID)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(SAGA_ID)).thenReturn(Optional.of(saga));
        when(sagas.findById(SAGA_ID)).thenReturn(Optional.of(saga));
        when(users.getCurrentUser("Bearer t")).thenReturn(new UserServiceClient.CurrentUser("recipient", "recipient@test.com", "Recipient"));
    }

    private void assertAcceptRejected(String token) {
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer t", SAGA_ID, token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_INVITATION_INVALID");
        verifyNoInteractions(outbox);
    }

    private void assertInvitationRejected(String token) {
        assertThatThrownBy(() -> service.giftInvitation("recipient", SAGA_ID, token))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_INVITATION_INVALID");
    }

    @Test
    void acceptGift_nullHash_isRejectedEvenWithToken() {
        givenPendingGift(null);
        assertAcceptRejected(TOKEN);
    }

    @Test
    void acceptGift_nullHash_isRejectedWithNullToken() {
        givenPendingGift(null);
        assertAcceptRejected(null);
    }

    @Test
    void acceptGift_nullToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertAcceptRejected(null);
    }

    @Test
    void acceptGift_emptyToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertAcceptRejected("");
    }

    @Test
    void acceptGift_wrongToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertAcceptRejected("wrong-token");
    }

    @Test
    void acceptGift_correctToken_succeeds() {
        givenPendingGift(hashToken(TOKEN));
        var response = service.acceptGift("recipient", "Bearer t", SAGA_ID, TOKEN);
        assertThat(response).isNotNull();
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void giftInvitation_nullHash_isRejectedEvenWithToken() {
        givenPendingGift(null);
        assertInvitationRejected(TOKEN);
    }

    @Test
    void giftInvitation_nullHash_isRejectedWithNullToken() {
        givenPendingGift(null);
        assertInvitationRejected(null);
    }

    @Test
    void giftInvitation_nullToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertInvitationRejected(null);
    }

    @Test
    void giftInvitation_emptyToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertInvitationRejected("");
    }

    @Test
    void giftInvitation_wrongToken_isRejected() {
        givenPendingGift(hashToken(TOKEN));
        assertInvitationRejected("wrong-token");
    }

    @Test
    void giftInvitation_correctToken_returnsInvitation() {
        givenPendingGift(hashToken(TOKEN));
        Map<String, Object> result = service.giftInvitation("recipient", SAGA_ID, TOKEN);
        assertThat(result).containsEntry("recipientEmail", "recipient@test.com");
        assertThat(result).containsKey("ticketIds");
    }
}
