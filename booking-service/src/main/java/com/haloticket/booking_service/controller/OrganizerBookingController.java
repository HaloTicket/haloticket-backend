package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventAccessClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.BookingDetailDto;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bookings/events/{eventId}/organizer")
@RequiredArgsConstructor
public class OrganizerBookingController {

    private final BookingRepository bookingRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final EventAccessClient eventAccess;
    private final EventServiceClient eventServiceClient;

    @GetMapping
    public ResponseEntity<Page<BookingDetailDto>> getEventBookings(
            @PathVariable String eventId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String showtimeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // the caller must hold ORDERS_VIEW of this event (event-service answers, with the caller token)
        eventAccess.require(eventId, "ORDERS_VIEW", authorization);
        if (search == null) {
            search = "";
        }
        
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Booking> bookings = bookingRepository.findByEventIdAndSearchAndShowtimeId(eventId, search, showtimeId, pageRequest);
        
        List<String> bookingIds = bookings.getContent().stream()
                .map(Booking::getId)
                .collect(Collectors.toList());
                
        Map<String, String> paymentMethods = Map.of();
        if (!bookingIds.isEmpty()) {
            try {
                paymentMethods = paymentServiceClient.getPaymentMethodsByBookingIds(bookingIds);
            } catch (Exception e) {
                // ignore or log exception
            }
        }
        
        Map<String, String> finalPaymentMethods = paymentMethods;
        Page<BookingDetailDto> dtoPage = bookings.map(b -> BookingDetailDto.builder()
                .id(b.getId())
                .bookingCode(b.getBookingCode())
                .customerId(b.getCustomerId())
                .customerName(b.getCustomerName())
                .customerEmail(b.getCustomerEmail())
                .eventId(b.getEventId())
                .totalAmount(b.getTotalAmount())
                .status(b.getStatus())
                .paymentMethod(finalPaymentMethods.get(b.getId()))
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .items(b.getItems())
                .build());
                
        return ResponseEntity.ok(dtoPage);
    }

    @GetMapping("/finance-facts")
    public ResponseEntity<FinanceFactsResponse> getFinanceFacts(@PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        boolean owner = admin || eventServiceClient.getMyEvents(authorization).stream().anyMatch(event -> eventId.equals(event.getId()));
        if (!owner) return ResponseEntity.notFound().build();
        List<BookingRepository.FinanceFact> rows = bookingRepository.findPaidFinanceFactsByEventId(eventId);
        return ResponseEntity.ok(new FinanceFactsResponse(eventId, rows.stream().map(BookingRepository.FinanceFact::getBookingId).toList(),
                rows.stream().mapToLong(row -> row.getAmount() == null ? 0L : Math.round(row.getAmount())).sum()));
    }

    public record FinanceFactsResponse(String eventId, List<String> paidBookingIds, long paidBookingAmount) {}
}
