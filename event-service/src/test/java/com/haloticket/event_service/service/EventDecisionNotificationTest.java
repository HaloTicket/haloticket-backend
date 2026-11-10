package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.client.*;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.repository.*;
import com.haloticket.event_service.service.impl.EventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the organiser is told when an event is approved or rejected: the reason, what to fix and where to open the event. */
@ExtendWith(MockitoExtension.class)
class EventDecisionNotificationTest {
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
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    EventServiceImpl service;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "notificationServiceClient", notificationClient);
        Event event = Event.builder().id("ev1").title("Đêm nhạc").eventApprovalStatus(EventApprovalStatus.PENDING).build();
        when(eventRepository.findById("ev1")).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenAnswer(call -> call.getArgument(0));
        StaffAssignment owner = mock(StaffAssignment.class);
        when(owner.getRole()).thenReturn(StaffRole.OWNER);
        when(owner.getStatus()).thenReturn(StaffAssignmentStatus.ACTIVE);
        when(owner.getUserId()).thenReturn("owner-1");
        when(staffAssignmentRepository.findByEventId("ev1")).thenReturn(List.of(owner));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentNotification() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient).publishNotification(captor.capture());
        return captor.getValue();
    }

    @Test
    void aRejectionTellsTheOrganiserTheReasonWhatToFixAndWhereToOpenTheEvent() throws Exception {
        String notes = "{\"flaggedFields\":[\"title\",\"dates\",\"hacker\"],\"noteText\":\"Sửa lại mô tả\","
                + "\"flaggedQuotes\":[{\"field\":\"description\",\"text\":\"bán vũ khí\"}]}";

        service.rejectEvent("ev1", "Mô tả quảng cáo hàng cấm", notes);

        Map<String, Object> sent = sentNotification();
        assertThat(sent.get("userId")).isEqualTo("owner-1");
        assertThat(sent.get("type")).isEqualTo("EVENT_REJECTED");
        String message = String.valueOf(sent.get("message"));
        assertThat(message).contains("Mô tả quảng cáo hàng cấm").contains("Mục cần sửa").contains("Tên & Mô tả").contains("Thời gian");
        assertThat(message).doesNotContain("hacker");
        JsonNode variables = json.readTree(String.valueOf(sent.get("variables")));
        assertThat(variables.path("eventId").asText()).isEqualTo("ev1");
        assertThat(variables.path("link").asText()).isEqualTo("/organizer/events/ev1/manage/edit");
        assertThat(variables.path("flaggedFields")).extracting(JsonNode::asText).containsExactly("title", "dates"); // unknown names dropped
    }

    @Test
    void aRejectionWithoutAnyFlagStillCarriesTheEventId() throws Exception {
        service.rejectEvent("ev1", null, "not json at all");

        Map<String, Object> sent = sentNotification();
        assertThat(String.valueOf(sent.get("message"))).contains("Cần chỉnh sửa lại thông tin").doesNotContain("Mục cần sửa");
        JsonNode variables = json.readTree(String.valueOf(sent.get("variables")));
        assertThat(variables.path("eventId").asText()).isEqualTo("ev1");
        assertThat(variables.has("flaggedFields")).isFalse();
    }

    @Test
    void aVeryLongReasonIsShortenedInTheNotificationButKeptWhole() {
        String longReason = "x".repeat(2000);

        service.rejectEvent("ev1", longReason, null);

        assertThat(String.valueOf(sentNotification().get("message")).length()).isLessThan(900);
        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getRejectionReason()).hasSize(2000);
    }

    @Test
    void anApprovalAlsoCarriesTheEventId() throws Exception {
        service.approveEvent("ev1");

        Map<String, Object> sent = sentNotification();
        assertThat(sent.get("type")).isEqualTo("EVENT_APPROVED");
        assertThat(json.readTree(String.valueOf(sent.get("variables"))).path("eventId").asText()).isEqualTo("ev1");
    }
}
