package com.haloticket.marketplace_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {
    @GetMapping("/me/ekyc/organizer-eligibility")
    ResaleSellerEligibility getResaleEligibility(@RequestHeader("Authorization") String authorization);

    @GetMapping("/gift-recipient")
    GiftRecipient getGiftRecipient(@RequestHeader("Authorization") String authorization, @RequestParam("email") String email);

    @GetMapping("/me")
    CurrentUser getCurrentUser(@RequestHeader("Authorization") String authorization);

    @GetMapping("/by-keycloak-id")
    PublicUser getUserDisplayName(@RequestParam("id") String keycloakId);

    record ResaleSellerEligibility(boolean eligible, String userId, Long identityVersion, String reasonCode) {}
    record GiftRecipient(String userId, String email, String fullName) {}
    record CurrentUser(String keycloakId, String email, String fullName) {}
    record PublicUser(String userId, String fullName) {}
}
