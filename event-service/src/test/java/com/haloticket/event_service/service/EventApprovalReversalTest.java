package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.client.*;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.*;
import com.haloticket.event_service.service.impl.EventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Admin takes an approval back / the organiser takes a review request back. */
@ExtendWith(MockitoExtension.class)
class EventApprovalReversalTest {
    @Mock EventRepository eventRepository;
    @Mock EventCategoryRepository categoryRepository;
    @Mock EventOrganizerRepository organizerRepository;
    @Mock SearchLogRepository searchLogRepository;
    @Mock TicketServiceClient ticketClient;
    @Mock PaymentServiceClient paymentClient;
    @Mock UserServiceClient userClient;
    @Mock UserInternalServiceClient userInternalClient;
    @Mock StaffAssignmentRepository staffAssignmentRepository;
    @Mock NotificationServiceClient notificationClient;
    @Mock BookingInternalServiceClient bookingClient;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    EventServiceImpl service;

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "notificationServiceClient", notificationClient);
        ReflectionTestUtils.setField(service, "bookingStatsClient", bookingClient);
    }

    private Event event(EventApprovalStatus status) {
        Event event = Event.builder().id("ev1").title("Đêm nhạc").eventApprovalStatus(status).eventStatus(EventStatus.APPROVED).build();
        when(eventRepository.findById("ev1")).thenReturn(Optional.of(event));
        return event;
    }

    private void stats(long sold) {
        BookingStatsResponse stats = new BookingStatsResponse();
        stats.setTotalTicketsSold(sold);
        when(bookingClient.getEventBookingStats("ev1")).thenReturn(stats);
    }

    private void owner(String userId) {
        StaffAssignment owner = mock(StaffAssignment.class);
        when(owner.getRole()).thenReturn(StaffRole.OWNER);
        when(owner.getStatus()).thenReturn(StaffAssignmentStatus.ACTIVE);
        when(owner.getUserId()).thenReturn(userId);
        when(staffAssignmentRepository.findByEventId("ev1")).thenReturn(List.of(owner));
    }

    @Test
    void anApprovedEventWithNoTicketsSoldGoesBackToPending() {
        Event event = event(EventApprovalStatus.APPROVED);
        stats(0);
        owner("owner-1");
        when(eventRepository.save(any(Event.class))).thenAnswer(call -> call.getArgument(0));

        service.revertApproval("ev1", "Phê duyệt nhầm");

        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.PENDING);
        assertThat(event.getEventStatus()).isEqualTo(EventStatus.PENDING);
        verify(notificationClient).publishNotification(any());
    }

    @Test
    void anApprovalCannotBeTakenBackOnceTicketsAreSold() {
        Event event = event(EventApprovalStatus.APPROVED);
        stats(3);

        assertThatThrownBy(() -> service.revertApproval("ev1", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).contains("3 vé");
                });
        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.APPROVED);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void whenTheSoldTicketsCannotBeReadTheApprovalIsKept() {
        Event event = event(EventApprovalStatus.APPROVED);
        when(bookingClient.getEventBookingStats("ev1")).thenThrow(new RuntimeException("booking-service down"));

        assertThatThrownBy(() -> service.revertApproval("ev1", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.APPROVED);
    }

    @Test
    void onlyAnApprovedEventCanBeReverted() {
        event(EventApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.revertApproval("ev1", "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(bookingClient);
    }

    @Test
    void theOwnerCanTakeAPendingRequestBackToDraft() {
        Event event = event(EventApprovalStatus.PENDING);
        owner("owner-1");
        when(userClient.getOrganizerEligibility("Bearer t")).thenReturn(new OrganizerEligibilityResponse(true, "owner-1", 1L, null));
        when(eventRepository.save(any(Event.class))).thenAnswer(call -> call.getArgument(0));

        service.cancelApprovalRequest("ev1", "Bearer t");

        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.DRAFT);
        assertThat(event.getEventStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void anotherOrganiserCannotTakeTheRequestBack() {
        Event event = event(EventApprovalStatus.PENDING);
        owner("owner-1");
        when(userClient.getOrganizerEligibility("Bearer t")).thenReturn(new OrganizerEligibilityResponse(true, "someone-else", 1L, null));

        assertThatThrownBy(() -> service.cancelApprovalRequest("ev1", "Bearer t"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.PENDING);
    }

    @Test
    void anEventWithNoKnownOwnerCannotBeCancelledByAnyone() {
        event(EventApprovalStatus.PENDING);
        when(staffAssignmentRepository.findByEventId("ev1")).thenReturn(List.of());
        when(userClient.getOrganizerEligibility("Bearer t")).thenReturn(new OrganizerEligibilityResponse(true, "anyone", 1L, null));

        assertThatThrownBy(() -> service.cancelApprovalRequest("ev1", "Bearer t"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aPendingUpdateOfALiveEventCannotBeCancelled() {
        Event event = event(EventApprovalStatus.UPDATE_PENDING);
        owner("owner-1");
        when(userClient.getOrganizerEligibility("Bearer t")).thenReturn(new OrganizerEligibilityResponse(true, "owner-1", 1L, null));

        assertThatThrownBy(() -> service.cancelApprovalRequest("ev1", "Bearer t"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(event.getEventApprovalStatus()).isEqualTo(EventApprovalStatus.UPDATE_PENDING);
        verify(eventRepository, never()).save(any());
    }
}
