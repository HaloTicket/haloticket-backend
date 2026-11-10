package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.client.UserServiceClient;
import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The names and contact details on the reconciliation rows: filled when the other services answer, empty (never an error) when not. */
class ReconciliationDetailsTest {
    private BookingRepository bookings;
    private UserServiceClient users;
    private EventServiceClient events;
    private PaymentServiceClient payments;
    private ReconciliationDetails details;

    @BeforeEach
    void setUp() {
        bookings = mock(BookingRepository.class);
        users = mock(UserServiceClient.class);
        events = mock(EventServiceClient.class);
        payments = mock(PaymentServiceClient.class);
        details = new ReconciliationDetails(bookings, users, events, payments);
    }

    private static Booking booking(String id, String customerId, String name, String email, String eventId, double total) {
        Booking b = new Booking();
        b.setId(id);
        b.setBookingCode("HALO-" + id);
        b.setCustomerId(customerId);
        b.setCustomerName(name);
        b.setCustomerEmail(email);
        b.setEventId(eventId);
        b.setTotalAmount(total);
        return b;
    }

    private static EventResponse event(String id, String title) {
        EventResponse e = new EventResponse();
        e.setId(id);
        e.setTitle(title);
        return e;
    }

    private static ReconciliationService.ExceptionItem exceptionOf(String bookingId, String provider) {
        return new ReconciliationService.ExceptionItem("x-" + bookingId, bookingId, "HALO-" + bookingId, null, "ISSUANCE_FAILED", 8400d,
                provider, "OPEN", "d", LocalDateTime.now(), null, null, null);
    }

    @Test
    void anExceptionRowGetsTheCustomerThePaymentMethodAndTheEvent() {
        when(bookings.findAllById(any())).thenReturn(List.of(booking("b1", "u1", "Tên trong đơn", "don@x.vn", "ev1", 8400)));
        when(users.getUserById("u1")).thenReturn(new UserServiceClient.InternalUser("u1", "vu@x.vn", "Trần Vũ", "0901234567"));
        when(events.getEventById("ev1")).thenReturn(event("ev1", "Tinh Hà Say Hi Concert"));
        when(payments.getPaymentMethodsByBookingIds(any())).thenReturn(Map.of("b1", "VIETQR"));

        var row = details.exceptionItems(List.of(exceptionOf("b1", null))).get(0);

        assertThat(row.customerName()).isEqualTo("Trần Vũ");
        assertThat(row.customerEmail()).isEqualTo("vu@x.vn");
        assertThat(row.customerPhone()).isEqualTo("0901234567");
        assertThat(row.paymentMethod()).isEqualTo("VIETQR");
        assertThat(row.eventId()).isEqualTo("ev1");
        assertThat(row.eventTitle()).isEqualTo("Tinh Hà Say Hi Concert");
        assertThat(row.detail()).isEqualTo("d"); // the original fields are kept
    }

    @Test
    void whenTheOtherServicesAreDownTheRowKeepsWhatTheBookingKnows() {
        when(bookings.findAllById(any())).thenReturn(List.of(booking("b1", "u1", "Tên trong đơn", "don@x.vn", "ev1", 8400)));
        when(users.getUserById(any())).thenThrow(new RuntimeException("user-service down"));
        when(events.getEventById(any())).thenThrow(new RuntimeException("event-service down"));
        when(payments.getPaymentMethodsByBookingIds(any())).thenThrow(new RuntimeException("payment-service down"));

        var row = details.exceptionItems(List.of(exceptionOf("b1", "VIETQR"))).get(0);

        assertThat(row.customerName()).isEqualTo("Tên trong đơn");
        assertThat(row.customerEmail()).isEqualTo("don@x.vn");
        assertThat(row.customerPhone()).isNull();
        assertThat(row.paymentMethod()).isEqualTo("VIETQR"); // the provider recorded on the exception
        assertThat(row.eventTitle()).isNull();
        assertThat(row.eventId()).isEqualTo("ev1");
    }

    @Test
    void aRowWhoseBookingIsGoneStillComesBack() {
        when(bookings.findAllById(any())).thenReturn(List.of());

        var row = details.exceptionItems(List.of(exceptionOf("gone", null))).get(0);

        assertThat(row.customerName()).isNull();
        assertThat(row.eventId()).isNull();
        assertThat(row.bookingCode()).isEqualTo("HALO-gone");
    }

    @Test
    void oneCustomerAndOneEventAreLookedUpOnceForManyRows() {
        when(bookings.findAllById(any())).thenReturn(List.of(
                booking("b1", "u1", null, null, "ev1", 100), booking("b2", "u1", null, null, "ev1", 200)));
        when(users.getUserById("u1")).thenReturn(new UserServiceClient.InternalUser("u1", "a@x.vn", "A", "0900000000"));
        when(events.getEventById("ev1")).thenReturn(event("ev1", "Show"));

        var rows = details.paidItems(List.of(
                new ReconciliationService.PaidWithoutTicketsItem("b1", "HALO-b1", LocalDateTime.now(), 2, 0, 30),
                new ReconciliationService.PaidWithoutTicketsItem("b2", "HALO-b2", LocalDateTime.now(), 1, 0, 40)));

        assertThat(rows).extracting(ReconciliationService.PaidWithoutTicketsItem::amount).containsExactly(100d, 200d);
        assertThat(rows).extracting(ReconciliationService.PaidWithoutTicketsItem::customerPhone).containsOnly("0900000000");
        assertThat(rows).extracting(ReconciliationService.PaidWithoutTicketsItem::eventTitle).containsOnly("Show");
        verify(users, times(1)).getUserById(eq("u1"));
        verify(events, times(1)).getEventById(eq("ev1"));
    }

    @Test
    void stockRowsGetTheEventNameAndKeepTheirNumbers() {
        when(events.getEventById("ev1")).thenReturn(event("ev1", "Show"));
        when(events.getEventById("ev2")).thenThrow(new RuntimeException("not found"));

        var rows = details.stockRows(List.of(
                Map.of("ticketTypeId", "t1", "eventId", "ev1", "initial", 10),
                Map.of("ticketTypeId", "t2", "eventId", "ev2", "initial", 5),
                Map.of("ticketTypeId", "t3")));

        assertThat(rows.get(0)).containsEntry("eventTitle", "Show").containsEntry("initial", 10).containsEntry("ticketTypeId", "t1");
        assertThat(rows.get(1)).containsEntry("eventTitle", null).containsEntry("eventId", "ev2");
        assertThat(rows.get(2)).containsEntry("eventTitle", null);
    }
}
