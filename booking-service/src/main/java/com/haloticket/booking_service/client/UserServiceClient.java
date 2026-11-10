package com.haloticket.booking_service.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Customer contact details for the admin reconciliation page (service to service: the gateway blocks /api/internal). */
@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/api/internal/users/{id}")
    InternalUser getUserById(@PathVariable("id") String id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InternalUser(String id, String email, String fullName, String phoneNumber) {}
}
