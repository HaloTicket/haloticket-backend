package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.service.OrganizerTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrganizerTransferControllerTest {
    private final OrganizerTransferService service = mock(OrganizerTransferService.class);
    private final OrganizerTransferController controller = new OrganizerTransferController(service);
    private final Authentication actor = new UsernamePasswordAuthenticationToken("authenticated-organizer", "");

    @Test
    void mutationRoutesPassTheAuthenticatedActorAndEventToService() {
        controller.moderateListing("Bearer token", "event", "listing",
                new OrganizerTransferController.ModerationRequest(OrganizerTransferController.ModerationAction.REJECT, "Reason"), actor);
        controller.cancelListing("Bearer token", "event", "listing", new OrganizerTransferController.CancellationRequest("Reason"), actor);
        controller.cancelGift("Bearer token", "event", "gift-saga", null, actor);

        verify(service).moderateListing("Bearer token", "authenticated-organizer", "event", "listing", false, "Reason");
        verify(service).cancelListing("Bearer token", "authenticated-organizer", "event", "listing", "Reason");
        verify(service).cancelGift("Bearer token", "authenticated-organizer", "event", "gift-saga", null);
    }

    @Test
    void missingModerationActionDoesNotReachMutationService() {
        assertThatThrownBy(() -> controller.moderateListing("Bearer token", "event", "listing",
                new OrganizerTransferController.ModerationRequest(null, null), actor))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
