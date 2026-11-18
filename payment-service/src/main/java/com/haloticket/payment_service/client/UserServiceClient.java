package com.haloticket.payment_service.client;

import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** Feign client gọi user-service về eKYC của người dùng. */
@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {
    /**
     * Kiểm tra người gọi có đủ điều kiện làm ban tổ chức (eKYC) hay không; kết quả có mã người dùng.
     *
     * @param authorization header Authorization chuyển tiếp từ người gọi
     * @return thông tin đủ điều kiện làm ban tổ chức
     */
    @GetMapping("/organizer-eligibility")
    OrganizerEligibilityResponse getOrganizerEligibility(@RequestHeader("Authorization") String authorization);
}
