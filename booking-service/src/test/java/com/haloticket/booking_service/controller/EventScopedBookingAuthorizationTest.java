package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventAccessClient;
import com.haloticket.booking_service.authz.EventAccessView;
import com.haloticket.booking_service.authz.EventScopeProperties;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.SeatHoldRequest;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.service.BookingCancellationService;
import com.haloticket.booking_service.service.BookingService;
import com.haloticket.booking_service.service.SeatHoldService;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The permission answers come from a stand-in for event-service that grants permissions by role as event-service does
 * (that table is tested there). The flags, the client with its cache and fail-closed behaviour and the controllers are
 * the real code.
 */
class EventScopedBookingAuthorizationTest {

    private static final List<String> OWNER = List.of("ORDERS_VIEW", "EVENT_EDIT", "CHECKIN_SCAN");
    private static final List<String> MANAGER = List.of("ORDERS_VIEW", "CHECKIN_SCAN");
    private static final List<String> CHECKIN = List.of("CHECKIN_SCAN", "CHECKIN_VIEW");

    EventServiceClient events = mock(EventServiceClient.class);
    BookingService bookings = mock(BookingService.class);
    BookingRepository repository = mock(BookingRepository.class);
    PaymentServiceClient payments = mock(PaymentServiceClient.class);
    SeatHoldService seatHolds = mock(SeatHoldService.class);
    EventScopeProperties scope = new EventScopeProperties();
    EventAccessClient access;
    BookingController bookingController;
    OrganizerBookingController organizerController;
    SeatController seatController;

    @BeforeEach
    void setUp() {
        scope.setEnabled(true);
        scope.getOrders().setEnabled(true);
        scope.getIdentity().setEnabled(true);
        scope.setCacheSeconds(0);
        access = new EventAccessClient(events, scope);
        bookingController = new BookingController(bookings, mock(BookingCancellationService.class), access, scope);
        organizerController = new OrganizerBookingController(repository, payments, access, scope, mock(com.haloticket.booking_service.client.EventServiceClient.class));
        seatController = new SeatController(seatHolds, scope);
        when(events.getMyAccess(anyString(), anyString())).thenAnswer(call -> {
            String event = call.getArgument(0);
            String token = call.getArgument(1);
            return switch (token) {
                case "Bearer owner-a" -> event.equals("A") ? view(event, OWNER, false) : denied();
                case "Bearer mgr-a" -> event.equals("A") ? view(event, MANAGER, false) : denied();
                case "Bearer chk-a" -> event.equals("A") ? view(event, CHECKIN, false) : denied();
                case "Bearer owner-b" -> event.equals("B") ? view(event, OWNER, false) : denied();
                case "Bearer root" -> view(event, OWNER, true);
                default -> denied();
            };
        });
        when(repository.findByEventIdAndSearchAndShowtimeId(anyString(), anyString(), any(), any(Pageable.class))).thenReturn(Page.empty());
        when(bookings.getBookingById("bk-1")).thenReturn(BookingResponse.builder().id("bk-1").eventId("A").customerId("kc-buyer").build());
        when(bookings.createBooking(any())).thenReturn(BookingResponse.builder().id("new").build());
    }

    private static EventAccessView view(String event, List<String> permissions, boolean admin) {
        return new EventAccessView(event, "u", admin, "X", "as", permissions);
    }

    private static EventAccessView denied() {
        throw new FeignException.Forbidden("forbidden", Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, StandardCharsets.UTF_8, null), new byte[0], Map.of());
    }

    private static Jwt jwt(String subject) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("sub", subject));
    }

    private static HttpStatus statusOf(Runnable call) {
        try {
            call.run();
            return HttpStatus.OK;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    private HttpStatus organizerList(String eventId, String token) {
        return statusOf(() -> organizerController.getEventBookings(eventId, token, null, null, 0, 20));
    }

    private HttpStatus read(String token, String subject) {
        return statusOf(() -> bookingController.getBooking("bk-1", subject == null ? null : jwt(subject), token));
    }

    @Test
    void theOrganizerOrderListNeedsOrdersViewOfThatEvent() {
        assertThat(organizerList("A", null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(organizerList("A", "Bearer stranger")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(organizerList("A", "Bearer owner-b")).isEqualTo(HttpStatus.FORBIDDEN);   // owner of another event
        assertThat(organizerList("A", "Bearer chk-a")).isEqualTo(HttpStatus.FORBIDDEN);     // check-in staff do not see orders
        assertThat(organizerList("A", "Bearer mgr-a")).isEqualTo(HttpStatus.OK);
        assertThat(organizerList("A", "Bearer owner-a")).isEqualTo(HttpStatus.OK);
        assertThat(organizerList("A", "Bearer root")).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEventServiceOutageClosesTheOrderList() {
        doThrow(new RuntimeException("connection refused")).when(events).getMyAccess(anyString(), anyString());
        assertThat(organizerList("A", "Bearer owner-a")).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(repository);
    }

    @Test
    void aBookingIsShownToItsBuyerAnOrdersViewerOfItsEventAndAnAdminOnly() {
        assertThat(read(null, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(read("Bearer buyer", "kc-buyer")).isEqualTo(HttpStatus.OK);                 // the buyer needs no event role
        assertThat(read("Bearer stranger", "kc-stranger")).isEqualTo(HttpStatus.NOT_FOUND);    // looks like a missing booking
        assertThat(read("Bearer owner-b", "kc-owner-b")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read("Bearer chk-a", "kc-chk-a")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read("Bearer mgr-a", "kc-mgr-a")).isEqualTo(HttpStatus.OK);
        assertThat(read("Bearer owner-a", "kc-owner-a")).isEqualTo(HttpStatus.OK);
        assertThat(read("Bearer root", "kc-root")).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theBuyerIsTheTokenSubjectNotTheBody() {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setCustomerId("someone-else");
        bookingController.createBooking(request, jwt("kc-buyer"));
        ArgumentCaptor<BookingCreateRequest> sent = ArgumentCaptor.forClass(BookingCreateRequest.class);
        verify(bookings).createBooking(sent.capture());
        assertThat(sent.getValue().getCustomerId()).isEqualTo("kc-buyer");

        assertThatThrownBy(() -> bookingController.createBooking(new BookingCreateRequest(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void seatHoldsFollowTheTokenAndAnonymousBrowsersKeepTheirSessionId() {
        SeatHoldRequest request = new SeatHoldRequest();
        request.setEventId("A");
        request.setSeatId("s1");
        request.setUserId("kc-victim");
        seatController.holdSeat(request, jwt("kc-attacker"));
        verify(seatHolds).holdSeat("A", null, "s1", "kc-attacker");
        seatController.releaseSeat(request, jwt("kc-attacker"));
        verify(seatHolds).releaseSeat("A", null, "s1", "kc-attacker");

        request.setUserId("session_123_abc");
        seatController.holdSeat(request, null);
        verify(seatHolds).holdSeat("A", null, "s1", "session_123_abc");

        request.setUserId("kc-victim");
        assertThatThrownBy(() -> seatController.releaseSeat(request, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void withTheSwitchesOffNothingChanges() {
        scope.setEnabled(false);
        assertThat(organizerList("A", null)).isEqualTo(HttpStatus.OK);
        assertThat(read(null, null)).isEqualTo(HttpStatus.OK);
        BookingCreateRequest request = new BookingCreateRequest();
        request.setCustomerId("whoever");
        assertThatCode(() -> bookingController.createBooking(request, null)).doesNotThrowAnyException();
        assertThat(request.getCustomerId()).isEqualTo("whoever");
        SeatHoldRequest hold = new SeatHoldRequest();
        hold.setUserId("any");
        seatController.holdSeat(hold, null);
        verify(seatHolds).holdSeat(null, null, null, "any");
        verifyNoInteractions(events);
    }
}
