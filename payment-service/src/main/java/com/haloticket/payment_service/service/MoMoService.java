package com.haloticket.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.config.HmacUtil;
import com.haloticket.payment_service.config.MoMoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tích hợp cổng thanh toán MoMo: tạo liên kết thanh toán {@code captureWallet} và kiểm tra chữ ký
 * HMAC SHA-256 của thông báo IPN.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MoMoService {

    private final MoMoConfig moMoConfig;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Gọi API MoMo để tạo đơn thanh toán ví và lấy liên kết thanh toán.
     * Mã đơn là {@code bookingId}, nếu thiếu thì sinh mã tạm theo thời gian. Liên kết trả về ưu tiên {@code payUrl},
     * không có thì dùng {@code deeplink}.
     *
     * @param amount số tiền (VND)
     * @param orderInfo nội dung đơn hàng; {@code null} thì dùng nội dung mặc định
     * @param bookingId mã booking, dùng làm mã đơn gửi MoMo
     * @return map gồm {@code paymentUrl} và {@code orderId}
     * @throws IllegalStateException khi MoMo lỗi hoặc không trả về liên kết thanh toán
     */
    public Map<String, String> createPaymentUrl(long amount, String orderInfo, String bookingId) {
        String requestId = UUID.randomUUID().toString();
        String orderId = bookingId != null ? bookingId : "ORDER_" + System.currentTimeMillis();
        String info = orderInfo != null ? orderInfo : "Thanh toan don hang HaloTicket " + orderId;
        String extraData = "";
        String requestType = "captureWallet";
        String redirectUrl = moMoConfig.getRedirectUrl() + "?bookingId=" + orderId + "&provider=momo";

        String rawSignature = String.format(
                "accessKey=%s&amount=%d&extraData=%s&ipnUrl=%s&orderId=%s&orderInfo=%s&partnerCode=%s&redirectUrl=%s&requestId=%s&requestType=%s",
                moMoConfig.getAccessKey(),
                amount,
                extraData,
                moMoConfig.getIpnUrl(),
                orderId,
                info,
                moMoConfig.getPartnerCode(),
                redirectUrl,
                requestId,
                requestType
        );

        String signature = HmacUtil.hmacSHA256(moMoConfig.getSecretKey(), rawSignature);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("partnerCode", moMoConfig.getPartnerCode());
        requestBody.put("partnerName", "HaloTicket");
        requestBody.put("storeId", "HaloTicketStore");
        requestBody.put("requestId", requestId);
        requestBody.put("amount", amount);
        requestBody.put("orderId", orderId);
        requestBody.put("orderInfo", info);
        requestBody.put("redirectUrl", redirectUrl);
        requestBody.put("ipnUrl", moMoConfig.getIpnUrl());
        requestBody.put("lang", "vi");
        requestBody.put("requestType", requestType);
        requestBody.put("autoCapture", true);
        requestBody.put("extraData", extraData);
        requestBody.put("signature", signature);

        Map<String, String> result = new HashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    moMoConfig.getEndpoint(),
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                String payUrl = (String) body.get("payUrl");
                String shortLink = (String) body.get("deeplink");
                result.put("paymentUrl", payUrl != null ? payUrl : shortLink);
                result.put("orderId", orderId);
                log.info("MoMo Payment URL created successfully for orderId {}: {}", orderId, payUrl);
                return result;
            }
        } catch (Exception e) {
            log.warn("Call to MoMo API endpoint failed or returned sandbox simulation fallback", e);
        }

        throw new IllegalStateException("MoMo did not create a payment URL");
    }

    /**
     * Kiểm tra chữ ký của thông báo IPN MoMo bằng cách ký lại chuỗi tham số với secret key và so sánh
     * không phân biệt hoa thường.
     *
     * @param params tham số MoMo gửi về, gồm cả {@code signature}
     * @return {@code true} nếu chữ ký khớp; {@code false} nếu thiếu hoặc sai chữ ký
     */
    public boolean verifySignature(Map<String, String> params) {
        String signature = params.get("signature");
        if (signature == null) return false;

        String rawSignature = String.format(
                "accessKey=%s&amount=%s&extraData=%s&message=%s&orderId=%s&orderInfo=%s&orderType=%s&partnerCode=%s&payType=%s&requestId=%s&responseTime=%s&resultCode=%s&transId=%s",
                moMoConfig.getAccessKey(),
                params.get("amount"),
                params.getOrDefault("extraData", ""),
                params.getOrDefault("message", ""),
                params.get("orderId"),
                params.getOrDefault("orderInfo", ""),
                params.getOrDefault("orderType", ""),
                moMoConfig.getPartnerCode(),
                params.getOrDefault("payType", ""),
                params.get("requestId"),
                params.getOrDefault("responseTime", ""),
                params.get("resultCode"),
                params.getOrDefault("transId", "")
        );

        String expectedSignature = HmacUtil.hmacSHA256(moMoConfig.getSecretKey(), rawSignature);
        return expectedSignature.equalsIgnoreCase(signature);
    }
}
