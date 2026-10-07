package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;

import java.util.List;

public interface PayoutService {
    PayoutResponse requestPayout(String organizerId, PayoutCreateRequest request);
    PayoutResponse approvePayout(String payoutId);
    List<PayoutResponse> getPendingPayouts();
    List<PayoutResponse> getAllAdminPayouts();
    List<PayoutResponse> getOrganizerPayouts(String organizerId);
}
