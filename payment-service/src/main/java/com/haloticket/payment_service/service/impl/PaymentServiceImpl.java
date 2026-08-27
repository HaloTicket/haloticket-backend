package com.haloticket.payment_service.service.impl;

import com.haloticket.payment_service.dto.request.PaymentCreateRequest;
import com.haloticket.payment_service.dto.response.PaymentResponse;
import com.haloticket.payment_service.entity.Payment;
import com.haloticket.payment_service.repository.PaymentRepository;
import com.haloticket.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;

    @Override
    public PaymentResponse createPayment(PaymentCreateRequest request) {
        return null;
    }
}