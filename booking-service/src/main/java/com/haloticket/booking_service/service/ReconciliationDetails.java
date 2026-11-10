package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.client.UserServiceClient;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who paid, how, and for which event, for the rows of the admin reconciliation page. Every lookup is best effort: a user,
 * event or payment service that does not answer leaves that field null and never fails the page (the order code and amount
 * still identify the row). At most LOOKUP_MAX distinct customers and events are looked up per page.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationDetails {

    static final int LOOKUP_MAX = 60;

    private final BookingRepository bookings;
    private final UserServiceClient users;
    private final EventServiceClient events;
    private final PaymentServiceClient payments;

    record Customer(String name, String email, String phone) {
        static final Customer NONE = new Customer(null, null, null);
    }

    /** Per-call caches so one customer or event is looked up once however many rows mention it. */
    private final class Lookups {
        private final Map<String, Booking> byBooking;
        private final Map<String, Customer> customers = new HashMap<>();
        private final Map<String, String> titles = new HashMap<>();
        private final Map<String, String> methods;

        Lookups(Collection<String> bookingIds) {
            this.byBooking = bookingIds.isEmpty() ? Map.of() : bookings.findAllById(bookingIds.stream().distinct().toList()).stream()
                    .collect(Collectors.toMap(Booking::getId, Function.identity(), (a, b) -> a));
            this.methods = methodsOf(byBooking.keySet());
        }

        Booking booking(String id) {
            return id == null ? null : byBooking.get(id);
        }

        String method(String bookingId) {
            return bookingId == null ? null : methods.get(bookingId);
        }

        Customer customer(Booking booking) {
            if (booking == null) return Customer.NONE;
            String key = booking.getCustomerId() == null ? "booking:" + booking.getId() : booking.getCustomerId();
            return customers.computeIfAbsent(key, k -> {
                String name = blankToNull(booking.getCustomerName());
                String email = blankToNull(booking.getCustomerEmail());
                String phone = null;
                if (booking.getCustomerId() != null && customers.size() < LOOKUP_MAX) {
                    try {
                        UserServiceClient.InternalUser user = users.getUserById(booking.getCustomerId());
                        if (user != null) {
                            name = firstNonBlank(user.fullName(), name);
                            email = firstNonBlank(user.email(), email);
                            phone = blankToNull(user.phoneNumber());
                        }
                    } catch (RuntimeException e) {
                        log.warn("[RECONCILIATION] customer lookup failed: {}", e.getClass().getSimpleName());
                    }
                }
                return new Customer(name, email, phone);
            });
        }

        String eventTitle(String eventId) {
            if (eventId == null || eventId.isBlank()) return null;
            if (titles.containsKey(eventId)) return titles.get(eventId);
            String title = null;
            if (titles.size() < LOOKUP_MAX) {
                try {
                    var event = events.getEventById(eventId);
                    title = event == null ? null : blankToNull(event.getTitle());
                } catch (RuntimeException e) {
                    log.warn("[RECONCILIATION] event lookup failed: {}", e.getClass().getSimpleName());
                }
            }
            titles.put(eventId, title);
            return title;
        }
    }

    private Map<String, String> methodsOf(Collection<String> bookingIds) {
        if (bookingIds.isEmpty()) return Map.of();
        try {
            Map<String, String> answer = payments.getPaymentMethodsByBookingIds(new ArrayList<>(bookingIds));
            return answer == null ? Map.of() : answer;
        } catch (RuntimeException e) {
            log.warn("[RECONCILIATION] payment methods lookup failed: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    public List<ReconciliationService.ExceptionItem> exceptionItems(List<ReconciliationService.ExceptionItem> items) {
        Lookups lookups = new Lookups(items.stream().map(ReconciliationService.ExceptionItem::bookingId).filter(java.util.Objects::nonNull).toList());
        List<ReconciliationService.ExceptionItem> out = new ArrayList<>();
        for (ReconciliationService.ExceptionItem item : items) {
            Booking booking = lookups.booking(item.bookingId());
            Customer customer = lookups.customer(booking);
            String eventId = booking == null ? null : booking.getEventId();
            String method = firstNonBlank(lookups.method(item.bookingId()), item.provider());
            out.add(item.withDetails(customer.name(), customer.email(), customer.phone(), method, eventId, lookups.eventTitle(eventId)));
        }
        return out;
    }

    public List<ReconciliationService.PaidWithoutTicketsItem> paidItems(List<ReconciliationService.PaidWithoutTicketsItem> items) {
        Lookups lookups = new Lookups(items.stream().map(ReconciliationService.PaidWithoutTicketsItem::bookingId).filter(java.util.Objects::nonNull).toList());
        List<ReconciliationService.PaidWithoutTicketsItem> out = new ArrayList<>();
        for (ReconciliationService.PaidWithoutTicketsItem item : items) {
            Booking booking = lookups.booking(item.bookingId());
            Customer customer = lookups.customer(booking);
            String eventId = booking == null ? null : booking.getEventId();
            out.add(item.withDetails(customer.name(), customer.email(), customer.phone(), lookups.method(item.bookingId()),
                    booking == null ? null : booking.getTotalAmount(), eventId, lookups.eventTitle(eventId)));
        }
        return out;
    }

    /** The ticket types with a stock discrepancy come from ticket-service with an event id only: add the event's name. */
    public List<Map<String, Object>> stockRows(List<Map<String, Object>> rows) {
        Lookups lookups = new Lookups(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            Object eventId = row.get("eventId");
            copy.put("eventTitle", eventId == null ? null : lookups.eventTitle(String.valueOf(eventId)));
            out.add(copy);
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        String a = blankToNull(first);
        return a != null ? a : blankToNull(second);
    }
}
