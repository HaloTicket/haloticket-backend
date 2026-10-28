package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventAccessClient;
import com.haloticket.booking_service.authz.EventScopeProperties;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.BookingResponse;
import com.haloticket.booking_service.service.BookingCancellationService;
import com.haloticket.booking_service.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final BookingCancellationService bookingCancellationService;
    private final EventAccessClient eventAccess;
    private final EventScopeProperties scope;

    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(@RequestBody BookingCreateRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        if (scope.identityOn()) {
            // the buyer is the signed-in person: a customerId in the body is not trusted
            String subject = subjectOf(jwt);
            if (request.getCustomerId() != null && !request.getCustomerId().equals(subject)) {
                log.warn("[IDENTITY_MISMATCH] booking customerId in the body differs from the token subject; the token wins");
            }
            request.setCustomerId(subject);
        }
        return new ResponseEntity<>(bookingService.createBooking(request), HttpStatus.CREATED);
    }

    /**
     * The booking id is a UUID but it also appears in URLs and emails, so it is not a secret. With the orders group on
     * the booking is shown to its buyer, to a member of its event who holds ORDERS_VIEW, and to a platform admin;
     * anyone else gets 404 (the same answer as for a booking that does not exist).
     */
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        BookingResponse booking = bookingService.getBookingById(id);
        if (scope.ordersOn()) {
            String subject = subjectOf(jwt);
            if (!subject.equals(booking.getCustomerId())) {
                try {
                    eventAccess.require(booking.getEventId(), "ORDERS_VIEW", authorization);
                } catch (ResponseStatusException denied) {
                    if (denied.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                            || denied.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND");
                    }
                    throw denied;
                }
            }
        }
        return ResponseEntity.ok(booking);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(bookingCancellationService.cancelOwnBooking(id, jwt.getSubject()));
    }

    @GetMapping("/by-code/{bookingCode}")
    public ResponseEntity<BookingDetailResponse> getBookingByCode(
            @PathVariable String bookingCode,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(bookingService.getBookingByCode(bookingCode, jwt.getSubject()));
    }

    private static String subjectOf(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        return jwt.getSubject();
    }
}
