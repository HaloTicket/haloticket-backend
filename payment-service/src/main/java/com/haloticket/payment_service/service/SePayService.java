package com.haloticket.payment_service.service;

import com.haloticket.payment_service.config.SePayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SePayService {

    private final SePayConfig sePayConfig;

    public Map<String, Object> createVietQrPayload(long amount, String bookingCode, String bookingId) {
        String bankCode = sePayConfig.getBankName();
        String accountNumber = sePayConfig.getAccountNo();
        String transferContent = "HALO " + (bookingCode != null && !bookingCode.isBlank() ? bookingCode : bookingId);
        
        String qrImageUrl = String.format("https://img.vietqr.io/image/%s-%s-compact2.png?amount=%d&addInfo=%s&accountName=%s",
                bankCode, 
                accountNumber, 
                amount, 
                URLEncoder.encode(transferContent, StandardCharsets.UTF_8),
                URLEncoder.encode(sePayConfig.getAccountName(), StandardCharsets.UTF_8));

        Map<String, Object> res = new HashMap<>();
        res.put("qrUrl", qrImageUrl);
        res.put("bankName", bankCode);
        res.put("accountNo", accountNumber);
        res.put("accountName", sePayConfig.getAccountName());
        res.put("amount", amount);
        res.put("transferContent", transferContent);
        res.put("bookingId", bookingId);

        log.info("Generated SePay VietQR payload for bookingId {}: {}", bookingId, qrImageUrl);
        return res;
    }
}
