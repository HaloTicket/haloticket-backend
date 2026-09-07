package com.haloticket.event_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "notification-service", path = "/api/notifications")
public interface NotificationServiceClient {

    @PostMapping("/publish")
    Object publishNotification(@RequestBody Map<String, Object> request);
}
