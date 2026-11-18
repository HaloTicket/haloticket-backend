package com.haloticket.booking_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Thông tin liên hệ khách hàng cho trang đối soát của admin (gọi giữa các service vì gateway chặn /api/internal). */
@FeignClient(name = "user-service")
public interface UserServiceClient {

    /**
     * @param id mã người dùng
     * @return thông tin liên hệ của người dùng
     */
    @GetMapping("/api/internal/users/{id}")
    InternalUser getUserById(@PathVariable("id") String id);

    /** Thông tin liên hệ của khách hàng do user-service trả về; bỏ qua các trường không dùng. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InternalUser(String id, String email, String fullName, String phoneNumber) {}
}
