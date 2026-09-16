package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.client.UserServiceClient;
import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.service.PaymentService;
import com.haloticket.payment_service.service.VNPayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final UserServiceClient userServiceClient;
    private final VNPayService vnPayService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentCreateRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    @PostMapping("/vnpay/create-url")
    public ResponseEntity<Map<String, String>> createVnPayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam(value = "bookingId", required = false) String bookingId,
            HttpServletRequest request) {
        String paymentUrl = vnPayService.createPaymentUrl(request, amount, orderInfo, bookingId);
        Map<String, String> response = new HashMap<>();
        response.put("paymentUrl", paymentUrl);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/vnpay/ipn")
    public ResponseEntity<Map<String, String>> vnpayIpn(@RequestParam Map<String, String> allParams) {
        Map<String, String> result = paymentService.processVnPayIpn(allParams);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/organizer-payout-account")
    public ResponseEntity<OrganizerPayoutAccountResponse> saveOrUpdatePayoutAccount(
            @RequestHeader("Authorization") String authorization,
            @RequestBody OrganizerPayoutAccountRequest request
    ) {
        var eligibility = requireEligibleOrganizer(authorization);
        request.setOrganizerUserId(eligibility.userId());
        request.setIdentityVersion(eligibility.identityVersion());
        OrganizerPayoutAccountResponse response = paymentService.saveOrUpdatePayoutAccount(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizer-payout-account/{userId}")
    public ResponseEntity<OrganizerPayoutAccountResponse> getPayoutAccountByUserId(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("userId") String ignoredUserId
    ) {
        String userId = requireEligibleOrganizer(authorization).userId();
        OrganizerPayoutAccountResponse response = paymentService.getPayoutAccountByUserId(userId);
        return ResponseEntity.ok(response);
    }

    private OrganizerEligibilityResponse requireEligibleOrganizer(
            String authorization
    ) {
        try {
            var eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility == null || !eligibility.eligible() || eligibility.userId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EKYC_REQUIRED");
            }
            return eligibility;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_STATUS_UNAVAILABLE");
        }
    }
}
