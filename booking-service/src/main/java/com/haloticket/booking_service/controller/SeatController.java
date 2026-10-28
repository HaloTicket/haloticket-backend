package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventScopeProperties;
import com.haloticket.booking_service.dto.request.SeatHoldRequest;
import com.haloticket.booking_service.service.SeatHoldService;
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
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private static final String ANONYMOUS_HOLD_PREFIX = "session_";

    private final SeatHoldService seatHoldService;
    private final EventScopeProperties scope;

    @PostMapping("/hold")
    public ResponseEntity<String> holdSeat(@RequestBody SeatHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        String holder = holderOf(request.getUserId(), jwt);
        boolean success = seatHoldService.holdSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), holder);
        if (success) {
            return ResponseEntity.ok("Seat held successfully");
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Seat is already held or unavailable");
        }
    }

    @PostMapping("/release")
    public ResponseEntity<String> releaseSeat(@RequestBody SeatHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        String holder = holderOf(request.getUserId(), jwt);
        seatHoldService.releaseSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), holder);
        return ResponseEntity.ok("Seat released successfully");
    }

    /**
     * Identity group: a signed-in person holds and releases under the token subject, whatever the body says (otherwise
     * anyone could release another person's seats by sending that person's id). Someone who is not signed in keeps the
     * anonymous browser id "session_..." the web creates; any other id without a token is refused.
     */
    private String holderOf(String requested, Jwt jwt) {
        if (!scope.identityOn()) return requested;
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            if (requested != null && !requested.equals(jwt.getSubject()) && !requested.startsWith(ANONYMOUS_HOLD_PREFIX)) {
                log.warn("[IDENTITY_MISMATCH] seat hold userId in the body differs from the token subject; the token wins");
            }
            return jwt.getSubject();
        }
        if (requested != null && requested.startsWith(ANONYMOUS_HOLD_PREFIX)) return requested;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}
