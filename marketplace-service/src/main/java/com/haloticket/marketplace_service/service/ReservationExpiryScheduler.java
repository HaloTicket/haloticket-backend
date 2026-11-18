package com.haloticket.marketplace_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ định kỳ giải phóng các lượt giữ chỗ vé bán lại đã quá hạn.
 */
@Component
@RequiredArgsConstructor
public class ReservationExpiryScheduler {
    private final TicketListingService ticketListingService;

    /**
     * Gọi {@code TicketListingService} để hủy các giữ chỗ hết hạn; chu kỳ cấu hình bằng {@code marketplace.reservation-expiry-scan-ms}.
     */
    @Scheduled(fixedDelayString = "${marketplace.reservation-expiry-scan-ms:30000}")
    public void releaseExpiredReservations() {
        ticketListingService.expireReservations();
    }
}
