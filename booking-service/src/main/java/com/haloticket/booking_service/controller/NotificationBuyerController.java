package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.enums.BookingStatus;
import com.haloticket.booking_service.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/internal/bookings/events/{eventId}/notification-buyers")
public class NotificationBuyerController {
    private static final ZoneId BOOKING_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final BookingRepository bookings;
    private final byte[] internalToken;

    public NotificationBuyerController(BookingRepository bookings,
            @Value("${authz.event-scope.internal-token:}") String internalToken) {
        this.bookings = bookings;
        this.internalToken = internalToken.isBlank() ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public List<String> buyerIds(@RequestHeader(name = "X-Internal-Token", required = false) String token,
                                 @PathVariable String eventId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cutoff,
                                 @RequestParam(defaultValue = "") String after,
                                 @RequestParam(defaultValue = "200") int limit) {
        if (internalToken.length == 0 || token == null
                || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (limit < 1 || limit > 200 || eventId.length() > 128 || after.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BUYER_PAGE");
        }
        // Booking records currently use updatedAt as the payment timestamp, as in reconciliation.
        return bookings.findNotificationBuyerIds(eventId, List.of(BookingStatus.PAID, BookingStatus.CONFIRMED),
                LocalDateTime.ofInstant(cutoff, BOOKING_TIME_ZONE), after, PageRequest.of(0, limit));
    }
}
