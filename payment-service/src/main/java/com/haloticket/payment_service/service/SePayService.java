package com.haloticket.payment_service.service;

import com.haloticket.payment_service.config.SePayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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

    public boolean checkPaymentActively(String bookingId, String bookingCode, double amount) {
        String userToken = sePayConfig.getUserToken();
        if (userToken == null || userToken.isBlank()) {
            return false;
        }

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(5000);
            RestTemplate restTemplate = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + userToken);
            headers.set("Content-Type", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://my.sepay.vn/userapi/transactions/list?limit=50",
                    HttpMethod.GET,
                    entity,
                    Map.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (body.get("transactions") instanceof List) {
                    List<Map<String, Object>> transactions = (List<Map<String, Object>>) body.get("transactions");
                    for (Map<String, Object> t : transactions) {
                        String content = (String) t.get("transaction_content");
                        if (content != null) {
                            String normalizedContent = content.toUpperCase().replace("-", "").replace(" ", "");
                            String normalizedBookingId = bookingId.replace("-", "").toUpperCase();
                            String normalizedBookingCode = bookingCode != null ? bookingCode.replace("-", "").toUpperCase() : null;

                            if (normalizedContent.contains("HALO") && 
                                (normalizedContent.contains(normalizedBookingId) || 
                                (normalizedBookingCode != null && normalizedContent.contains(normalizedBookingCode)))) {
                                double amountIn = Double.parseDouble(String.valueOf(t.get("amount_in")));
                                log.info("Found matching SePay transaction for {}. Expected amount: {}, Actual amountIn: {}", 
                                        bookingCode != null ? bookingCode : bookingId, amount, amountIn);
                                if (Double.compare(amountIn, amount) == 0) {
                                    return true;
                                } else {
                                    log.warn("Amount mismatch for {}: expected {}, got {}", bookingCode, amount, amountIn);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error actively checking SePay transactions", e);
        }
        return false;
    }
}
