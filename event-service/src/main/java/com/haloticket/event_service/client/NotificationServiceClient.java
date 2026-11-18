package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** Feign client gọi notification-service để gửi thông báo, kèm token nội bộ. */
@FeignClient(name = "notification-service", path = "/api/notifications", configuration = InternalTokenFeignConfig.class)
public interface NotificationServiceClient {

    /** Gửi một thông báo qua notification-service. */
    @PostMapping("/publish")
    Object publishNotification(@RequestBody Map<String, Object> request);
}
