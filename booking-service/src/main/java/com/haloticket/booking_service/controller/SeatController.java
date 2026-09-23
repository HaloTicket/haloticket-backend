package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.dto.request.SeatHoldRequest;
import com.haloticket.booking_service.service.SeatHoldService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatHoldService seatHoldService;

    @PostMapping("/hold")
    public ResponseEntity<String> holdSeat(@RequestBody SeatHoldRequest request) {
        boolean success = seatHoldService.holdSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), request.getUserId());
        if (success) {
            return ResponseEntity.ok("Seat held successfully");
        } else {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Seat is already held or unavailable");
        }
    }

    @PostMapping("/release")
    public ResponseEntity<String> releaseSeat(@RequestBody SeatHoldRequest request) {
        seatHoldService.releaseSeat(request.getEventId(), request.getShowtimeId(), request.getSeatId(), request.getUserId());
        return ResponseEntity.ok("Seat released successfully");
    }
}
