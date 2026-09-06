package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentCreateRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    @PostMapping("/organizer-payout-account")
    public ResponseEntity<OrganizerPayoutAccountResponse> saveOrUpdatePayoutAccount(@RequestBody OrganizerPayoutAccountRequest request) {
        OrganizerPayoutAccountResponse response = paymentService.saveOrUpdatePayoutAccount(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizer-payout-account/{userId}")
    public ResponseEntity<OrganizerPayoutAccountResponse> getPayoutAccountByUserId(@PathVariable("userId") String userId) {
        OrganizerPayoutAccountResponse response = paymentService.getPayoutAccountByUserId(userId);
        return ResponseEntity.ok(response);
    }
}