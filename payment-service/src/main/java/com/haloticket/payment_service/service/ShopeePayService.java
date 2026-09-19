package com.haloticket.payment_service.service;

import com.haloticket.payment_service.config.ShopeePayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopeePayService {

    private final ShopeePayConfig shopeePayConfig;

    public Map<String, String> createPaymentUrl(long amount, String orderInfo, String bookingId) {
        throw new IllegalStateException("ShopeePay merchant checkout endpoint is not configured");
    }
}
