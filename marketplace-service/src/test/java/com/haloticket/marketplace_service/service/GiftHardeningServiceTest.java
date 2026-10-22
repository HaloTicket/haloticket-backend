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
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GiftHardeningServiceTest {

    private TicketListingRepository listings;
    private MarketplaceSagaRepository sagas;
    private OutboxEventRepository outbox;
    private TicketTransferRepository transfers;
    private MarketplaceTransactionRepository transactions;
    private TicketServiceClient tickets;
    private EventServiceClient events;
    private UserServiceClient users;
    private ObjectMapper mapper;
    private EventTransferPolicyService gate;
    private TicketListingService service;

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        listings = mock(TicketListingRepository.class);
        sagas = mock(MarketplaceSagaRepository.class);
        outbox = mock(OutboxEventRepository.class);
        transfers = mock(TicketTransferRepository.class);
        transactions = mock(MarketplaceTransactionRepository.class);
        tickets = mock(TicketServiceClient.class);
        events = mock(EventServiceClient.class);
        users = mock(UserServiceClient.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        gate = mock(EventTransferPolicyService.class);

        service = new TicketListingService(
                listings, sagas, outbox, transfers, transactions,
                tickets, events, users, mapper, gate
        );
    }

    @Test
    void acceptGift_withNullOrBlankToken_throwsInvalidInvitation() {
        String sagaId = "saga-1";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender")
                .toUserId("recipient")
                .status(TransferStatus.PENDING)
                .invitationTokenHash(hashToken("valid-token"))
                .invitationExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-1")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));

        // null token
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer token", sagaId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_INVITATION_INVALID");

        // empty token
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer token", sagaId, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_INVITATION_INVALID");

        // wrong token
        assertThatThrownBy(() -> service.acceptGift("recipient", "Bearer token", sagaId, "wrong-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_INVITATION_INVALID");
    }

    @Test
    void acceptGift_withValidToken_succeeds() {
        String sagaId = "saga-1";
        String token = "secret123";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender")
                .toUserId("recipient")
                .recipientEmail("recipient@test.com")
                .status(TransferStatus.PENDING)
                .invitationTokenHash(hashToken(token))
                .invitationExpiresAt(Instant.now().plusSeconds(3600))
                .requireEkyc(false)
                .eventId("event-1")
                .ticketId("ticket-1")
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-1")
                .payload("{\"ticketId\":\"ticket-1\"}")
                .build();

        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));
        when(users.getCurrentUser("Bearer token")).thenReturn(new UserServiceClient.CurrentUser("recipient", "recipient@test.com", "Recipient"));

        var response = service.acceptGift("recipient", "Bearer token", sagaId, token);
        assertThat(response).isNotNull();
        assertThat(saga.getState()).isEqualTo(SagaState.WAITING_TRANSFER);
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void cancelGift_bySender_whenPending_succeedsAndRequestsRelease() {
        String sagaId = "saga-cancel";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender-user")
                .toUserId("recipient-user")
                .status(TransferStatus.PENDING)
                .ticketId("ticket-cancel")
                .invitationExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-cancel")
                .payload("{\"ticketId\":\"ticket-cancel\"}")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));

        var response = service.cancelGift("sender-user", sagaId);
        assertThat(response).isNotNull();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(transfer.getCancellationReason()).isEqualTo("SENDER_CANCELLED");
        verify(outbox).save(argThat(event ->
                event.getTopic().equals("haloticket.ticket.release.requested.v1")
        ));
    }

    @Test
    void cancelGift_byStranger_throwsForbidden() {
        String sagaId = "saga-cancel";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender-user")
                .toUserId("recipient-user")
                .status(TransferStatus.PENDING)
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-cancel")
                .payload("{}")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));

        assertThatThrownBy(() -> service.cancelGift("stranger-user", sagaId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FORBIDDEN");
    }

    @Test
    void cancelGift_whenAlreadyCompleted_throwsConflict() {
        String sagaId = "saga-cancel";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender-user")
                .toUserId("recipient-user")
                .status(TransferStatus.COMPLETED)
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.COMPLETED)
                .ticketId("ticket-cancel")
                .payload("{}")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));

        assertThatThrownBy(() -> service.cancelGift("sender-user", sagaId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GIFT_NOT_CANCELLABLE");
    }

    @Test
    void declineGift_byRecipient_whenPending_succeedsAndRequestsRelease() {
        String sagaId = "saga-decline";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender-user")
                .toUserId("recipient-user")
                .status(TransferStatus.PENDING)
                .ticketId("ticket-decline")
                .invitationExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-decline")
                .payload("{\"ticketId\":\"ticket-decline\"}")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));

        var response = service.declineGift("recipient-user", sagaId);
        assertThat(response).isNotNull();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(transfer.getCancellationReason()).isEqualTo("RECIPIENT_DECLINED");
        verify(outbox).save(argThat(event ->
                event.getTopic().equals("haloticket.ticket.release.requested.v1")
        ));
    }

    @Test
    void declineGift_byStranger_throwsForbidden() {
        String sagaId = "saga-decline";
        TicketTransfer transfer = TicketTransfer.builder()
                .sagaId(sagaId)
                .fromUserId("sender-user")
                .toUserId("recipient-user")
                .status(TransferStatus.PENDING)
                .build();
        MarketplaceSaga saga = MarketplaceSaga.builder()
                .id(sagaId)
                .type("GIFT_TRANSFER")
                .state(SagaState.WAITING_RECIPIENT)
                .ticketId("ticket-decline")
                .payload("{}")
                .build();
        when(transfers.findBySagaIdForUpdate(sagaId)).thenReturn(Optional.of(transfer));
        when(sagas.findByIdForUpdate(sagaId)).thenReturn(Optional.of(saga));
        when(sagas.findById(sagaId)).thenReturn(Optional.of(saga));

        assertThatThrownBy(() -> service.declineGift("stranger-user", sagaId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FORBIDDEN");
    }
}
