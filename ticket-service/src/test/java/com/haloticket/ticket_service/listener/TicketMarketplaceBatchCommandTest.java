package com.haloticket.ticket_service.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketOperation;
import com.haloticket.ticket_service.entity.OutboxEvent;
import com.haloticket.ticket_service.enums.TicketOperationState;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketMarketplaceBatchCommandTest {
    @Test
    void inconsistentPriorOperationCannotEmitBatchSuccess() {
        Fixture fixture = new Fixture();
        fixture.stored.put("saga:t1", TicketOperation.builder().operationId("saga:t1").sagaId("saga")
                .ticketId("t1").state(TicketOperationState.LOCKED).build());
        fixture.listener.lock(fixture.lockCommand());
        verify(fixture.outbox).save(argThat((OutboxEvent event) -> event.getPayload().contains("\"state\":\"REJECTED\"")));
        assertThat(fixture.second.getLockOperationId()).isNull();
    }
    @Test
    void invalidMemberPreventsEveryLockAndTransfer() {
        Fixture fixture = new Fixture();
        fixture.second.setOwnerId("another-owner");
        fixture.listener.lock(fixture.lockCommand());
        assertThat(fixture.first.getStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(fixture.second.getStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(fixture.first.getLockOperationId()).isNull();
        fixture.listener.transfer(fixture.transferCommand());
        assertThat(fixture.first.getOwnerId()).isEqualTo("seller");
        assertThat(fixture.second.getOwnerId()).isEqualTo("another-owner");
    }

    @Test
    void batchGiftTransfersOnceWithFreshQrAndMatchingReleaseCannotUndoIt() {
        Fixture fixture = new Fixture();
        fixture.listener.lock(fixture.lockCommand());
        assertThat(fixture.first.getLockOperationId()).isEqualTo("saga:t1");
        assertThat(fixture.second.getLockOperationId()).isEqualTo("saga:t2");
        fixture.listener.transfer(fixture.transferCommand());
        String firstQr = fixture.first.getQrCodeHash(), secondQr = fixture.second.getQrCodeHash();
        fixture.listener.transfer(fixture.transferCommand());
        fixture.listener.release(fixture.releaseCommand());
        assertThat(fixture.first.getOwnerId()).isEqualTo("recipient");
        assertThat(fixture.second.getOwnerId()).isEqualTo("recipient");
        assertThat(fixture.first.getAcquisitionType()).isEqualTo("GIFT");
        assertThat(fixture.second.getAcquisitionType()).isEqualTo("GIFT");
        assertThat(fixture.first.getQrVersion()).isEqualTo(2);
        assertThat(fixture.second.getQrVersion()).isEqualTo(2);
        assertThat(firstQr).isNotEqualTo(secondQr).isEqualTo(fixture.first.getQrCodeHash());
        assertThat(secondQr).isEqualTo(fixture.second.getQrCodeHash());
        assertThat(fixture.first.getStatus()).isEqualTo(TicketStatus.VALID);
    }

    @Test
    void releaseBeforeLockPreventsLateLock() {
        Fixture fixture = new Fixture();
        fixture.listener.release(fixture.releaseCommand());
        fixture.listener.lock(fixture.lockCommand());
        assertThat(fixture.first.getLockOperationId()).isNull();
        assertThat(fixture.second.getLockOperationId()).isNull();
    }

    @Test
    void batchCancellationOnlyReleasesMatchingLockOperations() {
        Fixture fixture = new Fixture();
        fixture.listener.lock(fixture.lockCommand());
        fixture.second.setLockOperationId("unrelated-lock");
        fixture.listener.release(fixture.releaseCommand());
        assertThat(fixture.first.getStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(fixture.first.getLockOperationId()).isNull();
        assertThat(fixture.second.getStatus()).isEqualTo(TicketStatus.LOCKED_FOR_TRANSFER);
        assertThat(fixture.second.getLockOperationId()).isEqualTo("unrelated-lock");
    }

    @Test
    void laterPartialSaleAndCancellationUseOriginalMemberLockIds() {
        Fixture fixture = new Fixture();
        fixture.listener.lock(fixture.lockCommand());
        fixture.listener.transfer("{\"sagaId\":\"purchase\",\"operationId\":\"purchase:TRANSFER\",\"ticketIds\":[\"t1\"],\"lockTicketIds\":[\"t1\",\"t2\"],\"lockOperationId\":\"saga\",\"fromUserId\":\"seller\",\"toUserId\":\"buyer\",\"purpose\":\"RESALE\",\"purchasePrice\":120000}");
        // A gift lock cannot be used for resale; the failed transfer leaves both members locked.
        assertThat(fixture.first.getOwnerId()).isEqualTo("seller");
        fixture.listener.release("{\"sagaId\":\"saga\",\"ticketIds\":[\"t2\"],\"lockTicketIds\":[\"t1\",\"t2\"]}");
        assertThat(fixture.second.getStatus()).isEqualTo(TicketStatus.VALID);
        assertThat(fixture.first.getStatus()).isEqualTo(TicketStatus.LOCKED_FOR_TRANSFER);
    }

    private static final class Fixture {
        final TicketRepository tickets = mock(TicketRepository.class);
        final TicketOperationRepository operations = mock(TicketOperationRepository.class);
        final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        final TicketMarketplaceCommandListener listener = new TicketMarketplaceCommandListener(new ObjectMapper(), tickets, operations, outbox);
        final Ticket first = Ticket.builder().id("t1").ownerId("seller").status(TicketStatus.VALID).qrVersion(1).qrCodeHash("old1").build();
        final Ticket second = Ticket.builder().id("t2").ownerId("seller").status(TicketStatus.VALID).qrVersion(1).qrCodeHash("old2").build();
        final Map<String, TicketOperation> stored = new HashMap<>();
        Fixture() {
            when(tickets.findWithLockById("t1")).thenReturn(Optional.of(first));
            when(tickets.findWithLockById("t2")).thenReturn(Optional.of(second));
            when(operations.findByOperationId(anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
            when(operations.save(any())).thenAnswer(inv -> {
                TicketOperation op = inv.getArgument(0);
                stored.put(op.getOperationId(), op);
                return op;
            });
        }
        String lockCommand() { return "{\"sagaId\":\"saga\",\"ticketIds\":[\"t1\",\"t2\"],\"sellerId\":\"seller\",\"purpose\":\"GIFT\"}"; }
        String transferCommand() { return "{\"sagaId\":\"saga\",\"operationId\":\"transfer\",\"ticketIds\":[\"t1\",\"t2\"],\"lockTicketIds\":[\"t1\",\"t2\"],\"lockOperationId\":\"saga\",\"fromUserId\":\"seller\",\"toUserId\":\"recipient\",\"purpose\":\"GIFT\"}"; }
        String releaseCommand() { return "{\"sagaId\":\"saga\",\"ticketIds\":[\"t1\",\"t2\"]}"; }
    }
}
