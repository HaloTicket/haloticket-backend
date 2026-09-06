package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.payment_service.dto.response.PaymentResponse;

public interface PaymentService {
    PaymentResponse createPayment(PaymentCreateRequest request);
    OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(OrganizerPayoutAccountRequest request);
    OrganizerPayoutAccountResponse getPayoutAccountByUserId(String userId);
}