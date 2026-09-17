package com.haloticket.payment_service.service;

import com.haloticket.payment_service.config.HmacUtil;
import com.haloticket.payment_service.config.ShopeePayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopeePayService {

    private final ShopeePayConfig shopeePayConfig;

    public Map<String, String> createPaymentUrl(long amount, String orderInfo, String bookingId) {
        String referenceId = bookingId != null ? bookingId : "SHOPEE_" + System.currentTimeMillis();
        String rawData = String.format("%s|%s|%d|%s",
                shopeePayConfig.getPartnerId(),
                referenceId,
                amount,
                shopeePayConfig.getSecretKey()
        );

        String signature = HmacUtil.hmacSHA256(shopeePayConfig.getSecretKey(), rawData);

        Map<String, String> result = new HashMap<>();
        String paymentUrl = shopeePayConfig.getRedirectUrl() + "?bookingId=" + referenceId + "&provider=shopeepay&status=success&sig=" + signature;
        
        result.put("paymentUrl", paymentUrl);
        result.put("referenceId", referenceId);
        log.info("ShopeePay Payment URL generated for referenceId {}: {}", referenceId, paymentUrl);
        return result;
    }
}
