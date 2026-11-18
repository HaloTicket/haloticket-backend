package com.haloticket.ticket_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lưới an toàn: trả lại tồn kho khi booking-service không gọi release (sập, quá thời gian, booking bị hoàn tác). */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReservationScheduler {

    private final StockReservationService stockReservations;

    /** Định kỳ quét các lượt giữ chỗ hết hạn theo từng lô 100, lặp lại khi lô còn đầy; lỗi chỉ được ghi log. */
    @Scheduled(fixedDelayString = "${ticket.stock-reservation.expiry-interval-ms:30000}")
    public void expireDueReservations() {
        try {
            while (stockReservations.expireDue(100) == 100) {
                // tiếp tục quét khi còn trả về lô đầy
            }
        } catch (Exception e) {
            log.error("[GA_RESERVATION] expiry sweep failed", e);
        }
    }
}
