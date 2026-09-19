package com.haloticket.event_service.service;

import com.haloticket.event_service.client.*;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.repository.*;
import com.haloticket.event_service.service.impl.EventServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceImplTest {
    @Mock EventRepository eventRepository;
    @Mock EventCategoryRepository categoryRepository;
    @Mock EventOrganizerRepository organizerRepository;
    @Mock SearchLogRepository searchLogRepository;
    @Mock TicketServiceClient ticketClient;
    @Mock PaymentServiceClient paymentClient;
    @Mock UserServiceClient userClient;
    @Mock StaffAssignmentRepository staffAssignmentRepository;

    @Test
    void createEventRejectsUnverifiedOrganizerBeforeWritingAnything() {
        EventServiceImpl service = new EventServiceImpl(
                eventRepository, categoryRepository, organizerRepository, searchLogRepository,
                ticketClient, paymentClient, userClient, staffAssignmentRepository
        );
        EventCreateRequest request = new EventCreateRequest();
        request.setUserId("spoofed-user");
        when(userClient.getOrganizerEligibility("Bearer token"))
                .thenReturn(new OrganizerEligibilityResponse(false, "real-user", null, "EKYC_NOT_STARTED"));

        assertThatThrownBy(() -> service.createEvent(request, "Bearer token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("EKYC_REQUIRED");

        verify(eventRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(organizerRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
