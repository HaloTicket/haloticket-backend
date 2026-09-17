package com.haloticket.marketplace_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {
    @GetMapping("/organizer-eligibility")
    ResaleSellerEligibility getResaleEligibility(@RequestHeader("Authorization") String authorization);

    record ResaleSellerEligibility(boolean eligible, String userId, Long identityVersion, String reasonCode) {}
}
