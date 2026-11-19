package com.haloticket.event_service.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.client.PaymentServiceClient;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.client.UserInternalServiceClient;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.controller.EventController;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.request.EventCreateRequest.TicketCreateRequest;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.enums.TicketSyncStatus;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.SearchLogRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.impl.EventServiceImpl;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventTicketSyncTest {
    static final String EVENT_ID = "abcdefgh-1111-2222-3333-444444444444";

    @Mock EventRepository eventRepository;
    @Mock EventCategoryRepository categoryRepository;
    @Mock EventOrganizerRepository organizerRepository;
    @Mock SearchLogRepository searchLogRepository;
    @Mock TicketServiceClient ticketClient;
    @Mock PaymentServiceClient paymentClient;
    @Mock UserServiceClient userClient;
    @Mock UserInternalServiceClient userInternalClient;
    @Mock StaffAssignmentRepository staffAssignmentRepository;
    @Mock EventCatalogWriter writer;
    @Mock EventAccessService access;

    EventServiceImpl service;
    ListAppender<ILoggingEvent> logs;
    Logger syncLogger;

    @BeforeEach
    void setUp() {
        TicketSyncService sync = new TicketSyncService(ticketClient, 3, 1);
        service = new EventServiceImpl(eventRepository, categoryRepository, organizerRepository, searchLogRepository,
                ticketClient, paymentClient, userClient, userInternalClient, staffAssignmentRepository, writer, sync,
                new ObjectMapper());
        syncLogger = (Logger) LoggerFactory.getLogger(TicketSyncService.class);
        logs = new ListAppender<>();
        logs.start();
        syncLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        syncLogger.detachAppender(logs);
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    static FeignException feign(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ticket-service/x", Map.of(), null,
                StandardCharsets.UTF_8, null);
        Response response = Response.builder().status(status).reason("r").request(request).headers(Map.of()).build();
        return FeignException.errorStatus("sync", response);
    }

    static TicketCreateRequest ticket(String name, Integer total, Integer initial) {
        TicketCreateRequest t = new TicketCreateRequest();
        t.setName(name);
        t.setPrice(100.0);
        t.setTotalQuantity(total);
        t.setInitialQuantity(initial);
        return t;
    }

    static EventShowtime showtime(String id, String name) {
        return EventShowtime.builder().id(id).name(name).build();
    }

    Event eventWith(EventShowtime... showtimes) {
        return Event.builder().id(EVENT_ID).title("T").showtimes(new ArrayList<>(List.of(showtimes))).build();
    }

    EventCreateRequest request(TicketCreateRequest... tickets) {
        EventCreateRequest request = new EventCreateRequest();
        request.setTickets(new ArrayList<>(List.of(tickets)));
        return request;
    }

    void saves(Event event) {
        when(userClient.getOrganizerEligibility("Bearer t")).thenReturn(new OrganizerEligibilityResponse(true, "u1", null, null));
        when(writer.save(any(), eq("u1"))).thenReturn(new EventCatalogWriter.Saved(event, null));
    }

    // --- item 1: failure is reported, retries are bounded -----------------------------------------------------------------

    @Test
    void failureAfterRetriesMarksFailedLogsErrorAndThrows() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenThrow(feign(503));

        assertThatThrownBy(() -> service.createEvent(request(ticket("VIP", 10, null)), "Bearer t"))
                .isInstanceOfSatisfying(TicketSyncFailedException.class, e -> {
                    assertThat(e.getEventId()).isEqualTo(EVENT_ID);
                    assertThat(e.getErrorCode()).isEqualTo("HTTP_503");
                });

        verify(ticketClient, times(3)).syncTicketTypesForEvent(any(), any(), anyList());
        verify(writer).markSync(EVENT_ID, TicketSyncStatus.FAILED, "HTTP_503");
        assertThat(event.getTicketsSyncStatus()).isEqualTo(TicketSyncStatus.FAILED);
        assertThat(event.getTicketsSyncError()).isEqualTo("HTTP_503");
        List<ILoggingEvent> errors = logs.list.stream().filter(l -> l.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(3);
        assertThat(errors.get(2).getFormattedMessage())
                .isEqualTo("[TICKET_SYNC_FAILED] eventId=abcdefgh attempt=3 code=HTTP_503");
    }

    @Test
    void controllerAnswers502WithTheDocumentedBody() {
        EventController controller = new EventController(service, access);
        ResponseEntity<Map<String, Object>> response = controller.ticketSyncFailed(new TicketSyncFailedException(EVENT_ID, "HTTP_503"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("code", "TICKETS_SYNC_FAILED").containsEntry("eventId", EVENT_ID)
                .containsKey("message");
    }

    @Test
    void transientFailureThenSuccessIsSyncedAfterTheRightNumberOfAttempts() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenThrow(feign(502)).thenReturn("ok");

        EventResponse response = service.createEvent(request(ticket("VIP", 10, null)), "Bearer t");

        verify(ticketClient, times(2)).syncTicketTypesForEvent(any(), any(), anyList());
        verify(writer).markSync(EVENT_ID, TicketSyncStatus.SYNCED, null);
        assertThat(event.getTicketsSyncStatus()).isEqualTo(TicketSyncStatus.SYNCED);
        assertThat(event.getTicketsSyncedAt()).isNotNull();
        assertThat(response.getTicketsSync().getStatus()).isEqualTo("SYNCED");
        assertThat(logs.list.stream().filter(l -> l.getLevel() == Level.ERROR)).hasSize(1);
    }

    @Test
    void aClientErrorIsNeverRetried() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenThrow(feign(400));

        assertThatThrownBy(() -> service.createEvent(request(ticket("VIP", 10, null)), "Bearer t"))
                .isInstanceOfSatisfying(TicketSyncFailedException.class, e -> assertThat(e.getErrorCode()).isEqualTo("HTTP_400"));

        verify(ticketClient, times(1)).syncTicketTypesForEvent(any(), any(), anyList());
    }

    @Test
    void aConnectionErrorIsRetried() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenThrow(new IllegalStateException("refused"));

        assertThatThrownBy(() -> service.createEvent(request(ticket("VIP", 10, null)), "Bearer t"))
                .isInstanceOfSatisfying(TicketSyncFailedException.class, e -> assertThat(e.getErrorCode()).isEqualTo("IllegalStateException"));
        verify(ticketClient, times(3)).syncTicketTypesForEvent(any(), any(), anyList());
    }

    @Test
    void a422IsNotRetriedAndCarriesTheDetailsToTheCaller() {
        Event event = eventWith();
        saves(event);
        Map<String, Object> details = Map.of("ticketTypeId", "tt1", "name", "VIP", "sold", 30, "held", 5, "minAllowed", 35);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList()))
                .thenThrow(new TicketSyncRejectedException("QUANTITY_BELOW_SOLD_AND_HELD", details, null));

        assertThatThrownBy(() -> service.createEvent(request(ticket("VIP", 10, null)), "Bearer t"))
                .isInstanceOfSatisfying(TicketSyncRejectedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("QUANTITY_BELOW_SOLD_AND_HELD");
                    assertThat(e.getEventId()).isEqualTo(EVENT_ID);
                    assertThat(e.getDetails()).containsEntry("name", "VIP").containsEntry("sold", 30)
                            .containsEntry("held", 5).containsEntry("minAllowed", 35);
                });
        verify(ticketClient, times(1)).syncTicketTypesForEvent(any(), any(), anyList());
        verify(writer).markSync(EVENT_ID, TicketSyncStatus.FAILED, "QUANTITY_BELOW_SOLD_AND_HELD");

        ResponseEntity<Map<String, Object>> response = new EventController(service, access)
                .ticketSyncRejected(new TicketSyncRejectedException("QUANTITY_BELOW_SOLD_AND_HELD", details, EVENT_ID));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "QUANTITY_BELOW_SOLD_AND_HELD").containsEntry("name", "VIP")
                .containsEntry("sold", 30).containsEntry("held", 5).containsEntry("minAllowed", 35);
    }

    @Test
    void theSyncRunsOutsideAnyTransactionAndOnlyTheWriterIsTransactional() throws Exception {
        Event event = eventWith();
        saves(event);
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenAnswer(call -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "ok";
        });

        service.createEvent(request(ticket("VIP", 10, null)), "Bearer t");

        assertThat(inTransaction).isFalse();
        // the orchestrating methods must not carry @Transactional (a self-invoked one would not apply anyway)
        assertThat(EventServiceImpl.class.getMethod("createEvent", EventCreateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(EventServiceImpl.class.getMethod("updateEvent", String.class, EventCreateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(EventServiceImpl.class.getMethod("resyncTickets", String.class).isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(EventCatalogWriter.class.getMethod("save", EventCreateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    // --- item 3: per-showtime syncs only; idempotent payloads; initialQuantity --------------------------------------------

    @Test
    void anEventWithShowtimesNeverCallsTheEventLevelSync() {
        Event event = eventWith(showtime("st1", "A"), showtime("st2", "B"));
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        service.createEvent(request(ticket("VIP", 10, null), ticket("STD", 20, null)), "Bearer t");

        verify(ticketClient, never()).syncTicketTypesForEvent(any(), isNull(), anyList());
        verify(ticketClient, times(1)).syncTicketTypesForEvent(eq(EVENT_ID), eq("st1"), anyList());
        verify(ticketClient, times(1)).syncTicketTypesForEvent(eq(EVENT_ID), eq("st2"), anyList());
    }

    @Test
    void anEventWithoutShowtimesSyncsOnceWithoutShowtimeId() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        service.createEvent(request(ticket("VIP", 10, null)), "Bearer t");

        verify(ticketClient, times(1)).syncTicketTypesForEvent(any(), any(), anyList());
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), isNull(), anyList());
    }

    @Test
    void aShowtimeUsesItsOwnTicketsAndKeepsTheExistingTicketTypeId() {
        Event event = eventWith(showtime("st1", "A"), showtime("st2", "B"));
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");
        TicketCreateRequest own = ticket("Own", 5, null);
        own.setId("tt-own");
        own.setShowtimeId("st1");
        EventCreateRequest request = new EventCreateRequest();
        EventCreateRequest.EventShowtimeRequest s1 = new EventCreateRequest.EventShowtimeRequest();
        s1.setName("A");
        s1.setTickets(List.of(own));
        request.setShowtimes(List.of(s1));
        request.setTickets(List.of(ticket("Shared", 7, null)));

        service.createEvent(request, "Bearer t");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> st1 = ArgumentCaptor.forClass(List.class);
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), eq("st1"), st1.capture());
        assertThat(st1.getValue()).extracting(TicketCreateRequest::getName).containsExactly("Own");
        assertThat(st1.getValue().get(0).getId()).isEqualTo("tt-own");
        assertThat(st1.getValue().get(0).getShowtimeId()).isEqualTo("st1");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> st2 = ArgumentCaptor.forClass(List.class);
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), eq("st2"), st2.capture());
        assertThat(st2.getValue()).extracting(TicketCreateRequest::getName).containsExactly("Shared");
        assertThat(st2.getValue().get(0).getId()).isNull();
    }

    @Test
    void savingTwiceSendsTheSamePayloads() {
        Event event = eventWith(showtime("st1", "A"), showtime("st2", "B"));
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");
        EventCreateRequest request = request(ticket("VIP", 10, null), ticket("STD", 20, 25));

        service.createEvent(request, "Bearer t");
        service.createEvent(request, "Bearer t");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> payloads = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> showtimes = ArgumentCaptor.forClass(String.class);
        verify(ticketClient, times(4)).syncTicketTypesForEvent(eq(EVENT_ID), showtimes.capture(), payloads.capture());
        List<List<TicketCreateRequest>> all = payloads.getAllValues();
        assertThat(all.get(0)).isEqualTo(all.get(2));
        assertThat(all.get(1)).isEqualTo(all.get(3));
        assertThat(showtimes.getAllValues()).containsExactly("st1", "st2", "st1", "st2");
    }

    @Test
    void theSyncCarriesInitialQuantityAndFallsBackToTotalQuantity() {
        Event event = eventWith();
        saves(event);
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        service.createEvent(request(ticket("OnlyTotal", 100, null), ticket("Both", 20, 50)), "Bearer t");

        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> payload = ArgumentCaptor.forClass(List.class);
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), isNull(), payload.capture());
        assertThat(payload.getValue()).extracting(TicketCreateRequest::getInitialQuantity).containsExactly(100, 50);
    }

    // --- resync ----------------------------------------------------------------------------------------------------------

    @Test
    void resyncSendsTheStoredSnapshotAndMarksSynced() throws Exception {
        Event event = eventWith();
        event.setTicketsData(new ObjectMapper().writeValueAsString(List.of(ticket("VIP", 100, null))));
        event.setTicketsSyncStatus(TicketSyncStatus.FAILED);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        EventResponse response = service.resyncTickets(EVENT_ID);

        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> payload = ArgumentCaptor.forClass(List.class);
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), isNull(), payload.capture());
        assertThat(payload.getValue().get(0).getInitialQuantity()).isEqualTo(100);
        verify(writer).markSync(EVENT_ID, TicketSyncStatus.SYNCED, null);
        assertThat(response.getTicketsSync().getStatus()).isEqualTo("SYNCED");
    }

    @Test
    void resyncSendsEachShowtimeItsOwnStampedTickets() throws Exception {
        Event event = eventWith(showtime("st1", "A"), showtime("st2", "B"));
        TicketCreateRequest a = ticket("A-VIP", 10, null);
        a.setShowtimeId("st1");
        TicketCreateRequest b = ticket("B-VIP", 20, null);
        b.setShowtimeId("st2");
        event.setTicketsData(new ObjectMapper().writeValueAsString(List.of(a, b)));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        service.resyncTickets(EVENT_ID);

        @SuppressWarnings("unchecked") ArgumentCaptor<List<TicketCreateRequest>> p1 = ArgumentCaptor.forClass(List.class);
        verify(ticketClient).syncTicketTypesForEvent(eq(EVENT_ID), eq("st1"), p1.capture());
        assertThat(p1.getValue()).extracting(TicketCreateRequest::getName).containsExactly("A-VIP");
        verify(ticketClient, never()).syncTicketTypesForEvent(any(), isNull(), anyList());
    }

    @Test
    void resyncFailureMarksFailed() throws Exception {
        Event event = eventWith();
        event.setTicketsData(new ObjectMapper().writeValueAsString(List.of(ticket("VIP", 100, null))));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenThrow(feign(500));

        assertThatThrownBy(() -> service.resyncTickets(EVENT_ID)).isInstanceOf(TicketSyncFailedException.class);

        verify(ticketClient, times(3)).syncTicketTypesForEvent(any(), any(), anyList());
        verify(writer).markSync(EVENT_ID, TicketSyncStatus.FAILED, "HTTP_500");
    }

    @Test
    void resyncWithNothingStoredIs409() {
        Event event = eventWith();
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.resyncTickets(EVENT_ID)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("NO_TICKETS_TO_SYNC");
    }

    @Test
    void resyncEndpointChecksTheEditPermissionFirst() {
        when(access.require(EVENT_ID, Permission.EVENT_EDIT, "Bearer t"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"));
        EventController controller = new EventController(service, access);

        assertThatThrownBy(() -> controller.resyncTickets(EVENT_ID, "Bearer t")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FORBIDDEN_ROLE");
        verify(ticketClient, never()).syncTicketTypesForEvent(any(), any(), anyList());
        verify(eventRepository, never()).findById(any());
    }

    @Test
    void resyncEndpointAnswers200ForAnAllowedCaller() throws Exception {
        Event event = eventWith();
        event.setTicketsData(new ObjectMapper().writeValueAsString(List.of(ticket("VIP", 100, null))));
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.syncTicketTypesForEvent(any(), any(), anyList())).thenReturn("ok");

        ResponseEntity<EventResponse> response = new EventController(service, access).resyncTickets(EVENT_ID, "Bearer t");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTicketsSync().getStatus()).isEqualTo("SYNCED");
        verify(access).require(EVENT_ID, Permission.EVENT_EDIT, "Bearer t");
    }

    // --- the response says whether the tickets are real ---------------------------------------------------------------------

    @Test
    void ticketsSyncIsUnavailableWhenTicketServiceCannotBeRead() {
        Event event = eventWith();
        event.setTicketsData("[{\"name\":\"VIP\",\"price\":100,\"totalQuantity\":1000}]");
        event.setTicketsSyncStatus(TicketSyncStatus.SYNCED);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.getAllTicketTypesByEventId(EVENT_ID)).thenThrow(feign(503));

        EventResponse response = service.getEventById(EVENT_ID);

        assertThat(response.getTicketsSync().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.getTicketsSync().getError()).isNotBlank();
        assertThat(response.getTickets()).isNotNull(); // the snapshot is still shown, flagged
    }

    @Test
    void ticketsSyncReportsTheStoredMarkerWhenTicketServiceAnswers() {
        Event event = eventWith();
        event.setTicketsSyncStatus(TicketSyncStatus.FAILED);
        event.setTicketsSyncError("HTTP_503");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(ticketClient.getAllTicketTypesByEventId(EVENT_ID)).thenReturn(List.of(Map.of("name", "VIP", "price", 100)));

        EventResponse response = service.getEventById(EVENT_ID);

        assertThat(response.getTicketsSync().getStatus()).isEqualTo("FAILED");
        assertThat(response.getTicketsSync().getError()).isEqualTo("HTTP_503");
    }

    @Test
    void unknownEventIdReturnsNullInsteadOfAnotherEvent() {
        when(eventRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.getEventById("missing")).isNull();
        verify(eventRepository, never()).findAll();
    }
}
