package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;

import java.util.Map;
import java.util.List;

public interface PaymentService {
    PaymentResponse createPayment(PaymentCreateRequest request);
    OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(OrganizerPayoutAccountRequest request);
    OrganizerPayoutAccountResponse getPayoutAccountByUserId(String userId);

    Map<String, String> processVnPayIpn(Map<String, String> allParams);
    Map<String, Object> createVietQrPayload(long amount, String bookingCode, String bookingId);
    Map<String, String> processSepayWebhook(Map<String, Object> webhookData);

    Map<String, String> createMoMoPaymentUrl(long amount, String orderInfo, String bookingId);
    Map<String, String> processMoMoIpn(Map<String, String> allParams);

    Map<String, String> createZaloPayPaymentUrl(long amount, String orderInfo, String bookingId);
    Map<String, String> processZaloPayIpn(Map<String, Object> callbackData);

    Map<String, String> createShopeePayPaymentUrl(long amount, String orderInfo, String bookingId);
    Map<String, String> processShopeePayIpn(Map<String, Object> callbackData);

    PaymentResponse getPaymentStatusByBookingId(String bookingId);
    PaymentResponse confirmSandboxReturn(String bookingId, String provider);
    Map<String, String> getPaymentMethodsByBookingIds(List<String> bookingIds);
}
