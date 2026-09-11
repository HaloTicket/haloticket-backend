package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {
    @GetMapping("/organizer-eligibility")
    OrganizerEligibilityResponse getOrganizerEligibility(@RequestHeader("Authorization") String authorization);
}
