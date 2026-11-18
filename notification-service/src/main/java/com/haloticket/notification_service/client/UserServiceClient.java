package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Feign client gọi {@code user-service} chỉ để biết mã người dùng của người gọi: thông báo được gửi tới
 * hoặc {@code subject} của token hoặc mã này.
 */
@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {

    /**
     * Lấy kết quả kiểm tra điều kiện eKYC của người gọi, trong đó có mã người dùng nội bộ.
     *
     * @param authorization header {@code Authorization} của người gọi
     * @return kết quả kiểm tra điều kiện kèm mã người dùng
     */
    @GetMapping("/organizer-eligibility")
    Eligibility getOrganizerEligibility(@RequestHeader("Authorization") String authorization);

    /** Kết quả kiểm tra điều kiện eKYC: có đủ điều kiện không và mã người dùng; bỏ qua các trường lạ khi đọc JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Eligibility(boolean eligible, String userId) {}
}
