package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.event_service.dto.response.OrganizerPayoutAccountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "payment-service", path = "/api/payments")
public interface PaymentServiceClient {

    @PostMapping("/organizer-payout-account")
    OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(@RequestBody OrganizerPayoutAccountRequest request);

    @GetMapping("/organizer-payout-account/{userId}")
    OrganizerPayoutAccountResponse getPayoutAccountByUserId(@PathVariable("userId") String userId);
}
