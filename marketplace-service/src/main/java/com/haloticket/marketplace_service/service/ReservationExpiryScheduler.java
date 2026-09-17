package com.haloticket.marketplace_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationExpiryScheduler {
    private final TicketListingService ticketListingService;

    @Scheduled(fixedDelayString = "${marketplace.reservation-expiry-scan-ms:30000}")
    public void releaseExpiredReservations() {
        ticketListingService.expireReservations();
    }
}
