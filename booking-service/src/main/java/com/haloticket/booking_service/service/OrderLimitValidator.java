package com.haloticket.booking_service.service;

import com.haloticket.booking_service.client.TicketOrderLimitClient;
import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.request.BookingItemRequest;
import com.haloticket.booking_service.dto.response.TicketTypeDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Kiểm tra số vé mỗi đơn theo giới hạn tối thiểu và tối đa của từng loại vé (lấy từ ticket-service, có cache 30 giây).
 * Nếu không đọc được giới hạn thì bỏ qua kiểm tra thay vì chặn đơn.
 */
@Slf4j
@Component
public class OrderLimitValidator {

    static final long TTL_MILLIS = 30_000L;
    static final long LOOKUP_TIMEOUT_MILLIS = 3_000L;
    private static final int MAX_ENTRIES = 2000;

    /** Giới hạn số vé mỗi đơn của một loại vé, kèm thời điểm lưu vào cache. */
    private record Limits(Integer min, Integer max, long storedAt) {}

    private final TicketOrderLimitClient ticketClient;
    private final long lookupTimeoutMillis;
    private final Map<String, Limits> cache = new ConcurrentHashMap<>();
    private final ExecutorService lookups = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "order-limit-lookup");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Constructor dùng trong ứng dụng, với thời gian chờ tra cứu mặc định {@code LOOKUP_TIMEOUT_MILLIS}.
     * @param ticketClient client gọi ticket-service lấy giới hạn của loại vé
     */
    @Autowired
    public OrderLimitValidator(TicketOrderLimitClient ticketClient) {
        this(ticketClient, LOOKUP_TIMEOUT_MILLIS);
    }

    /**
     * Constructor cho phép đặt thời gian chờ tra cứu (dùng khi kiểm thử).
     * @param ticketClient client gọi ticket-service lấy giới hạn của loại vé
     * @param lookupTimeoutMillis thời gian chờ tối đa của mỗi lần tra cứu, tính bằng mili giây
     */
    OrderLimitValidator(TicketOrderLimitClient ticketClient, long lookupTimeoutMillis) {
        this.ticketClient = ticketClient;
        this.lookupTimeoutMillis = lookupTimeoutMillis;
    }

    /**
     * Kiểm tra tổng số vé của từng loại trong đơn nằm trong giới hạn của loại đó.
     * @param request yêu cầu tạo đơn
     * @throws ResponseStatusException HTTP 422 {@code QUANTITY_OUT_OF_RANGE} khi số vé ít hơn mức tối thiểu hoặc nhiều hơn mức tối đa
     */
    public void validate(BookingCreateRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) return;

        // một giới hạn áp dụng cho mọi vé cùng loại trong đơn nên cộng dồn các dòng lặp; số lượng không dương
        // không phải số vé và được để cho các bước kiểm tra phía sau xử lý
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (BookingItemRequest item : request.getItems()) {
            if (item.getTicketTypeId() == null || item.getTicketTypeId().isBlank()) continue;
            int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
            if (quantity <= 0) continue;
            byType.merge(item.getTicketTypeId(), quantity, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : byType.entrySet()) {
            Limits limits = limitsOf(entry.getKey());
            if (limits == null) continue;
            int quantity = entry.getValue();
            boolean tooFew = limits.min() != null && quantity < limits.min();
            boolean tooMany = limits.max() != null && quantity > limits.max();
            if (tooFew || tooMany) {
                log.warn("[ORDER_LIMIT] ticketType={} quantity={} min={} max={}", entry.getKey(), quantity, limits.min(), limits.max());
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "QUANTITY_OUT_OF_RANGE");
            }
        }
    }

    /** Giới hạn của một loại vé, hoặc null khi không đọc được (bên gọi sẽ bỏ qua kiểm tra). Có cache và giới hạn thời gian chờ. */
    private Limits limitsOf(String ticketTypeId) {
        long now = System.currentTimeMillis();
        Limits cached = cache.get(ticketTypeId);
        if (cached != null && now - cached.storedAt() < TTL_MILLIS) return cached;

        Future<TicketTypeDto> lookup = lookups.submit(() -> ticketClient.getTicketTypeById(ticketTypeId));
        try {
            TicketTypeDto dto = lookup.get(lookupTimeoutMillis, TimeUnit.MILLISECONDS);
            if (dto == null || (dto.getMinPerOrder() == null && dto.getMaxPerOrder() == null)) {
                log.warn("[ORDER_LIMIT_SKIPPED] ticket type {} returned no order limits", ticketTypeId);
                return null;
            }
            Limits fresh = new Limits(dto.getMinPerOrder(), dto.getMaxPerOrder(), now);
            if (cache.size() >= MAX_ENTRIES) cache.clear();
            cache.put(ticketTypeId, fresh);
            return fresh;
        } catch (InterruptedException e) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[ORDER_LIMIT_SKIPPED] interrupted while reading the limits of ticket type {}", ticketTypeId);
            return null;
        } catch (Exception e) {
            lookup.cancel(true);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[ORDER_LIMIT_SKIPPED] cannot read the limits of ticket type {}: {}", ticketTypeId, cause.getClass().getSimpleName());
            return null;
        }
    }
}
