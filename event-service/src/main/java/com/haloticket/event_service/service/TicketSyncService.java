package com.haloticket.event_service.service;

import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.dto.request.EventCreateRequest.TicketCreateRequest;
import com.haloticket.event_service.exception.TicketSyncFailedException;
import com.haloticket.event_service.exception.TicketSyncRejectedException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Đẩy các loại vé sang ticket-service với số lần thử lại có giới hạn. Không bao giờ chạy trong giao dịch cơ sở dữ liệu (nơi gọi
 * không dùng transaction). Chỉ thử lại khi lỗi kết nối, hết thời gian chờ hoặc 5xx; mọi 4xx là lỗi của bên gọi nên không thử lại:
 * 422 thành {@link TicketSyncRejectedException}, các 4xx khác thành {@link TicketSyncFailedException}.
 */
@Component
public class TicketSyncService {
    private static final Logger log = LoggerFactory.getLogger(TicketSyncService.class);

    /** Một lần gọi sang ticket-service: {@code showtimeId} là null với sự kiện không có suất diễn. */
    public record Batch(String showtimeId, List<TicketCreateRequest> tickets) {}

    private final TicketServiceClient ticketServiceClient;
    private final int maxAttempts;
    private final long backoffMs;

    /**
     * Tạo dịch vụ với số lần thử và thời gian chờ ban đầu cấu hình được ({@code event.ticket-sync.max-attempts}, {@code event.ticket-sync.backoff-ms}); số lần thử tối thiểu là 1.
     * @param ticketServiceClient client gọi ticket-service
     * @param maxAttempts số lần thử tối đa cho mỗi lô
     * @param backoffMs thời gian chờ trước lần thử lại đầu tiên, mỗi lần sau nhân 3
     */
    public TicketSyncService(TicketServiceClient ticketServiceClient,
                             @Value("${event.ticket-sync.max-attempts:3}") int maxAttempts,
                             @Value("${event.ticket-sync.backoff-ms:300}") long backoffMs) {
        this.ticketServiceClient = ticketServiceClient;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    /**
     * Gửi lần lượt từng lô và dừng ở lô đầu tiên thất bại.
     *
     * @param eventId id sự kiện
     * @param batches các lô vé cần đồng bộ
     * @throws TicketSyncRejectedException khi ticket-service từ chối nội dung (422)
     * @throws TicketSyncFailedException khi gọi thất bại sau khi hết số lần thử
     */
    public void syncAll(String eventId, List<Batch> batches) {
        for (Batch batch : batches) {
            syncBatch(eventId, batch.showtimeId(), batch.tickets());
        }
    }

    /**
     * Đồng bộ một lô, thử lại khi gặp lỗi tạm thời với thời gian chờ tăng gấp 3 mỗi lần.
     * @param eventId id sự kiện
     * @param showtimeId id suất diễn, null nếu cấp sự kiện
     * @param tickets danh sách vé của lô
     * @throws TicketSyncRejectedException khi bị từ chối (422), không thử lại
     * @throws TicketSyncFailedException khi gặp 4xx khác, hết số lần thử hoặc bị ngắt luồng
     */
    void syncBatch(String eventId, String showtimeId, List<TicketCreateRequest> tickets) {
        long wait = backoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ticketServiceClient.syncTicketTypesForEvent(eventId, showtimeId, tickets);
                return;
            } catch (TicketSyncRejectedException rejected) {
                log.warn("[TICKET_SYNC_REJECTED] eventId={} attempt={} code={}", shortId(eventId), attempt, rejected.getCode());
                throw rejected.forEvent(eventId);
            } catch (RuntimeException e) {
                String code = codeOf(e);
                log.error("[TICKET_SYNC_FAILED] eventId={} attempt={} code={}", shortId(eventId), attempt, code);
                if (isClientError(e) || attempt == maxAttempts) {
                    throw new TicketSyncFailedException(eventId, code);
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TicketSyncFailedException(eventId, code);
                }
                wait *= 3;
            }
        }
    }

    /** Cho biết lỗi có phải phản hồi 4xx của ticket-service (không đáng thử lại) hay không. */
    private static boolean isClientError(RuntimeException e) {
        return e instanceof FeignException fe && fe.status() >= 400 && fe.status() < 500;
    }

    /** Mã lỗi ngắn, không bao giờ là thông điệp gốc: trạng thái HTTP (dạng {@code HTTP_xxx}) hoặc tên lớp ngoại lệ, cắt tối đa 64 ký tự. */
    static String codeOf(RuntimeException e) {
        String code = e instanceof FeignException fe && fe.status() > 0 ? "HTTP_" + fe.status() : e.getClass().getSimpleName();
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    /** Rút gọn id sự kiện còn tối đa 8 ký tự để ghi log. */
    private static String shortId(String eventId) {
        if (eventId == null) return "null";
        return eventId.length() > 8 ? eventId.substring(0, 8) : eventId;
    }
}
