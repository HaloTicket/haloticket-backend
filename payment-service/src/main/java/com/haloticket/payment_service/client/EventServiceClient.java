package com.haloticket.payment_service.client;

import com.haloticket.payment_service.dto.response.EventFinanceSource;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import java.util.List;

/** Feign client gọi event-service để lấy danh sách sự kiện phục vụ tính toán tài chính. */
@FeignClient(name = "event-service", path = "/api/events")
public interface EventServiceClient {
    /**
     * Lấy các sự kiện của người gọi (ban tổ chức).
     *
     * @param authorization header Authorization chuyển tiếp từ người gọi
     * @return danh sách sự kiện
     */
    @GetMapping("/my-events")
    List<EventFinanceSource> getMyEvents(@RequestHeader("Authorization") String authorization);
    /**
     * Lấy toàn bộ sự kiện (không cần xác thực).
     *
     * @return danh sách sự kiện
     */
    @GetMapping
    List<EventFinanceSource> getAllEvents();
}
