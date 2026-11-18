package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** Client Feign gọi user-service để kiểm tra điều kiện làm nhà tổ chức (eKYC) của người dùng hiện tại. */
@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {
    /**
     * Kiểm tra người dùng đã đủ điều kiện eKYC để làm nhà tổ chức hay chưa.
     * @param authorization header {@code Authorization} chứa JWT của người dùng
     * @return kết quả đánh giá điều kiện
     */
    @GetMapping("/organizer-eligibility")
    OrganizerEligibilityResponse getOrganizerEligibility(@RequestHeader("Authorization") String authorization);
}
