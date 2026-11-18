package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.entity.MarketplaceSaga;
import com.haloticket.marketplace_service.enums.SagaState;
import com.haloticket.marketplace_service.repository.MarketplaceSagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.EnumSet;

/**
 * Tác vụ định kỳ khôi phục các saga bị treo hoặc quá hạn ở trạng thái chờ hay đang bù trừ.
 */
@Component @RequiredArgsConstructor @Slf4j
public class SagaTimeoutScheduler {
    private final MarketplaceSagaRepository sagaRepository;
    private final TicketListingService listingService;
    private final ResalePurchaseService purchaseService;

    /**
     * Quét tối đa 50 saga đã đến hạn thử lại. Saga mua vé bán lại đang chờ chuyển vé thì thử chuyển lại;
     * saga quá hạn thì yêu cầu nhả vé với lý do {@code SAGA_EXPIRED}; saga đang bù trừ thì hẹn thử lại sau 10 giây.
     */
    @Scheduled(fixedDelayString = "${marketplace.saga.poll-delay-ms:5000}")
    public void recoverExpiredSagas() {
        Instant now = Instant.now();
        for (MarketplaceSaga saga : sagaRepository.findTop50ByStateInAndNextRetryAtBeforeOrderByCreatedAtAsc(EnumSet.of(SagaState.WAITING_LOCK, SagaState.WAITING_RECIPIENT, SagaState.WAITING_TRANSFER, SagaState.COMPENSATING), now)) {
            try {
                if ("RESALE_PURCHASE".equals(saga.getType()) && saga.getState() == SagaState.WAITING_TRANSFER) {
                    purchaseService.retryTimedOutTransfer(saga.getId());
                } else if (saga.getDeadlineAt() != null && saga.getDeadlineAt().isBefore(now) && saga.getState() != SagaState.COMPENSATING) {
                    listingService.requestRelease(saga.getId(), "SAGA_EXPIRED");
                } else if (saga.getState() == SagaState.COMPENSATING) {
                    saga.setNextRetryAt(now.plusSeconds(10)); saga.setRetryCount((saga.getRetryCount() == null ? 0 : saga.getRetryCount()) + 1); sagaRepository.save(saga);
                }
            } catch (Exception exception) { log.warn("Could not recover marketplace saga {}", saga.getId(), exception); }
        }
    }
}
