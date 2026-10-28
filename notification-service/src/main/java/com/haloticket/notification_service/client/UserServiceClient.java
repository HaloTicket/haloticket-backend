package com.haloticket.notification_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

/** Only to learn the caller's user-service id: notifications are addressed to either the token subject or that id. */
@FeignClient(name = "user-service", path = "/api/users/me/ekyc")
public interface UserServiceClient {

    @GetMapping("/organizer-eligibility")
    Eligibility getOrganizerEligibility(@RequestHeader("Authorization") String authorization);

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Eligibility(boolean eligible, String userId) {}
}
