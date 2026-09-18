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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${payment.sandbox-return-confirmation-enabled:false}")
    private boolean sandboxReturnConfirmationEnabled;

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@RequestBody PaymentCreateRequest request) {
        return new ResponseEntity<>(paymentService.createPayment(request), HttpStatus.CREATED);
    }

    // 1. VNPAY
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

    // 2. SePay (VietQR 24/7)
    @PostMapping("/vietqr/create-payload")
    public ResponseEntity<Map<String, Object>> createVietQrPayload(
            @RequestParam("amount") long amount,
            @RequestParam(value = "bookingCode", required = false) String bookingCode,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createVietQrPayload(amount, bookingCode, bookingId));
    }

    @PostMapping("/sepay/webhook")
    public ResponseEntity<Map<String, String>> sepayWebhook(@RequestBody Map<String, Object> webhookData) {
        return ResponseEntity.ok(paymentService.processSepayWebhook(webhookData));
    }

    // 3. MoMo
    @PostMapping("/momo/create-url")
    public ResponseEntity<Map<String, String>> createMoMoUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createMoMoPaymentUrl(amount, orderInfo, bookingId));
    }

    @PostMapping("/momo/ipn")
    public ResponseEntity<Map<String, String>> momoIpn(@RequestParam Map<String, String> allParams) {
        return ResponseEntity.ok(paymentService.processMoMoIpn(allParams));
    }

    // 4. ZaloPay
    @PostMapping("/zalopay/create-url")
    public ResponseEntity<Map<String, String>> createZaloPayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createZaloPayPaymentUrl(amount, orderInfo, bookingId));
    }

    @PostMapping("/zalopay/ipn")
    public ResponseEntity<Map<String, String>> zalopayIpn(@RequestBody Map<String, Object> callbackData) {
        return ResponseEntity.ok(paymentService.processZaloPayIpn(callbackData));
    }

    // 5. ShopeePay
    @PostMapping("/shopeepay/create-url")
    public ResponseEntity<Map<String, String>> createShopeePayUrl(
            @RequestParam("amount") long amount,
            @RequestParam(value = "orderInfo", required = false) String orderInfo,
            @RequestParam("bookingId") String bookingId) {
        return ResponseEntity.ok(paymentService.createShopeePayPaymentUrl(amount, orderInfo, bookingId));
    }

    @PostMapping("/shopeepay/ipn")
    public ResponseEntity<Map<String, String>> shopeepayIpn(@RequestBody Map<String, Object> callbackData) {
        return ResponseEntity.ok(paymentService.processShopeePayIpn(callbackData));
    }

    // Payment verification for frontend polling/callback
    @GetMapping("/verify/{bookingId}")
    public ResponseEntity<PaymentResponse> getPaymentStatusByBookingId(@PathVariable("bookingId") String bookingId) {
        PaymentResponse response = paymentService.getPaymentStatusByBookingId(bookingId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sandbox/confirm-return")
    public ResponseEntity<PaymentResponse> confirmSandboxReturn(
            @RequestParam("bookingId") String bookingId,
            @RequestParam("provider") String provider) {
        if (!sandboxReturnConfirmationEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        PaymentResponse response = paymentService.confirmSandboxReturn(bookingId, provider);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
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
