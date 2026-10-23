package com.haloticket.ticket_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safety net: gives stock back when booking-service never called release (crash, timeout, rolled back booking). */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReservationScheduler {

    private final StockReservationService stockReservations;

    @Scheduled(fixedDelayString = "${ticket.stock-reservation.expiry-interval-ms:30000}")
    public void expireDueReservations() {
        try {
            while (stockReservations.expireDue(100) == 100) {
                // keep sweeping while full batches come back
            }
        } catch (Exception e) {
            log.error("[GA_RESERVATION] expiry sweep failed", e);
        }
    }
}
