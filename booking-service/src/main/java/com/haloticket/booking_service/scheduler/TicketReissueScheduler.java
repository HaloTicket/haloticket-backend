package com.haloticket.booking_service.scheduler;

import com.haloticket.booking_service.client.TicketStockClient;
import com.haloticket.booking_service.config.GaReservationProperties;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import com.haloticket.booking_service.service.BookingReissueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Đơn đã thanh toán nhưng vé chưa xuất hiện (lỗi tạm thời ở ticket-service, khởi động lại, mất thông điệp) được tự kích lại
 * mà không cần ai bấm: vài phút một lần, các đơn thanh toán quá GRACE phút mà số vé ít hơn số đã mua sẽ được gửi lại xác nhận,
 * tối đa MAX_ATTEMPTS lần mỗi đơn. Việc phát hành vé là idempotent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketReissueScheduler {

    private final BookingRepository bookings;
    private final TicketStockClient ticketStock;
    private final GaReservationProperties ticketProperties;
    private final BookingReissueService reissue;

    @Value("${booking.reissue.grace-minutes:3}")
    private int graceMinutes;
    @Value("${booking.reissue.max-attempts:5}")
    private int maxAttempts;
    @Value("${booking.reissue.lookback-hours:48}")
    private int lookbackHours;
    @Value("${booking.reissue.batch-size:50}")
    private int batchSize;

    /**
     * Quét các đơn đã thanh toán thiếu vé (hỏi số vé đã phát hành ở ticket-service), rồi yêu cầu phát hành lại,
     * có giãn cách giữa các lần thử và giới hạn số lần. Bỏ qua cả lượt khi chưa cấu hình token nội bộ hoặc ticket-service không trả lời.
     */
    @Scheduled(fixedDelayString = "${booking.reissue.interval-ms:120000}", initialDelayString = "${booking.reissue.initial-delay-ms:90000}")
    public void redrivePaidOrdersWithoutTickets() {
        String token = ticketProperties.getInternalToken();
        if (token == null || token.isBlank()) return; // ticket-service vẫn sẽ từ chối yêu cầu đếm
        LocalDateTime now = LocalDateTime.now();
        List<BookingRepository.PaidCandidate> candidates = bookings.findPaidCandidates(
                now.minusHours(lookbackHours), now.minusMinutes(graceMinutes), PageRequest.of(0, Math.max(1, Math.min(batchSize, 200))));
        if (candidates.isEmpty()) return;

        Map<String, Integer> issued;
        try {
            Map<String, Map<String, Integer>> answer = ticketStock.issuedCounts(token,
                    Map.of("bookingIds", candidates.stream().map(BookingRepository.PaidCandidate::getBookingId).toList()));
            if (answer == null || answer.get("counts") == null) return;
            issued = new HashMap<>(answer.get("counts"));
        } catch (RuntimeException e) {
            log.warn("[REISSUE] scheduler skipped: ticket-service unavailable ({})", e.getClass().getSimpleName());
            return;
        }
        int requested = 0;
        for (BookingRepository.PaidCandidate candidate : candidates) {
            long expected = candidate.getExpectedTickets() == null ? 0 : candidate.getExpectedTickets();
            if (expected <= 0 || issued.getOrDefault(candidate.getBookingId(), 0) >= expected) continue;
            Booking booking = bookings.findById(candidate.getBookingId()).orElse(null);
            if (booking == null) continue;
            int attempts = booking.getReissueAttempts() == null ? 0 : booking.getReissueAttempts();
            if (attempts >= maxAttempts) continue;
            // giãn cách: lần tự động thứ n chờ n x 2 phút sau lần trước
            if (booking.getLastReissueAt() != null && booking.getLastReissueAt().plusMinutes(2L * (attempts + 1)).isAfter(now)) continue;
            try {
                if (reissue.reissue(booking).result() == BookingReissueService.Result.REQUESTED) requested++;
            } catch (RuntimeException e) {
                log.warn("[REISSUE] could not re-drive bookingId={}: {}", booking.getId(), e.getClass().getSimpleName());
            }
        }
        if (requested > 0) log.info("[REISSUE] re-drove {} paid order(s) without tickets", requested);
    }
}
