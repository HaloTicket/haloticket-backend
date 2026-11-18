package com.haloticket.marketplace_service.service;

import com.haloticket.marketplace_service.entity.EventTransferPolicyState;
import com.haloticket.marketplace_service.repository.EventTransferPolicyStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;

/**
 * Kiểm tra cổng chính sách chuyển nhượng của sự kiện (bán lại, tặng vé) dựa trên bản sao chính sách trong cơ sở dữ liệu.
 * Sự kiện chưa có chính sách được coi là cho phép (tương thích sự kiện cũ).
 */
@Service @RequiredArgsConstructor
public class EventTransferPolicyService {
    private final EventTransferPolicyStateRepository repo;

    /**
     * Bảo đảm sự kiện đang cho phép bán lại vé.
     *
     * @param eventId mã sự kiện
     * @throws ResponseStatusException HTTP 403 với mã {@code RESALE_DISABLED_BY_EVENT}, {@code RESALE_NOT_STARTED}
     *         hoặc {@code RESALE_CLOSED} khi bán lại bị tắt, chưa mở hoặc đã đóng
     */
    public void requireResaleEnabled(String eventId) {
        Optional<EventTransferPolicyState> opt = repo.findById(eventId);
        if (opt.isEmpty()) return; // chưa có cổng chính sách thì mặc định cho phép (sự kiện cũ trước khi cấu hình chính sách)
        EventTransferPolicyState s = opt.get();
        if (!s.isResaleEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_DISABLED_BY_EVENT");
        Instant now = Instant.now();
        if (s.getResaleOpensAt() != null && now.isBefore(s.getResaleOpensAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_NOT_STARTED");
        if (s.getResaleClosesAt() != null && now.isAfter(s.getResaleClosesAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESALE_CLOSED");
    }

    /**
     * Bảo đảm sự kiện đang cho phép tặng vé.
     *
     * @param eventId mã sự kiện
     * @throws ResponseStatusException HTTP 403 với mã {@code GIFT_DISABLED_BY_EVENT}, {@code GIFT_NOT_STARTED}
     *         hoặc {@code GIFT_CLOSED} khi tặng vé bị tắt, chưa mở hoặc đã đóng
     */
    public void requireGiftEnabled(String eventId) {
        Optional<EventTransferPolicyState> opt = repo.findById(eventId);
        if (opt.isEmpty()) return; // sự kiện cũ: mặc định cho phép
        EventTransferPolicyState s = opt.get();
        if (!s.isGiftEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_DISABLED_BY_EVENT");
        Instant now = Instant.now();
        if (s.getGiftOpensAt() != null && now.isBefore(s.getGiftOpensAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_NOT_STARTED");
        if (s.getGiftClosesAt() != null && now.isAfter(s.getGiftClosesAt()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GIFT_CLOSED");
    }

    /**
     * Lấy bản sao chính sách chuyển nhượng của sự kiện.
     *
     * @param eventId mã sự kiện
     * @return chính sách nếu đã được cấu hình
     */
    public Optional<EventTransferPolicyState> getState(String eventId) {
        return repo.findById(eventId);
    }
}
