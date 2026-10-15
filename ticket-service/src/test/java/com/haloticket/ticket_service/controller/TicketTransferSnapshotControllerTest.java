package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketTransferSnapshotControllerTest {
    @Test void missingOrIncorrectServiceCredentialCannotReadOwnership() {
        TicketRepository repository = mock(TicketRepository.class);
        var controller = new TicketTransferSnapshotController(repository, "test-service-secret");
        assertThatThrownBy(() -> controller.snapshot("ticket", null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.snapshot("ticket", "incorrect")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new TicketTransferSnapshotController(repository, "").snapshot("ticket", ""))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test void authorizedSnapshotReturnsLockAndStableIdsWithoutQr() {
        TicketRepository repository = mock(TicketRepository.class);
        when(repository.findById("ticket")).thenReturn(Optional.of(Ticket.builder().id("ticket")
                .eventId("event").ownerId("owner").lockOperationId("lock").status(TicketStatus.VALID)
                .ticketType(TicketType.builder().id("type").showtimeId("show").build())
                .qrCodeHash("must-never-leak").giftTransferCount(2).build()));
        var snapshot = new TicketTransferSnapshotController(repository, "secret").snapshot("ticket", "secret");
        assertThat(snapshot.ownerId()).isEqualTo("owner");
        assertThat(snapshot.lockOperationId()).isEqualTo("lock");
        assertThat(snapshot.ticketTypeId()).isEqualTo("type");
        assertThat(snapshot.showtimeId()).isEqualTo("show");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(snapshot)
                .path("giftTransferCount").asInt(-1)).isEqualTo(2);
        assertThat(snapshot.toString()).doesNotContain("must-never-leak");
    }
}
