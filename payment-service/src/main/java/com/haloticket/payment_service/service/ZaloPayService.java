package com.haloticket.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.payment_service.config.HmacUtil;
import com.haloticket.payment_service.config.ZaloPayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** Tích hợp ZaloPay: tạo đơn hàng lấy {@code order_url} và kiểm tra mac của callback. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZaloPayService {

    private final ZaloPayConfig zaloPayConfig;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Gọi API ZaloPay tạo đơn thanh toán, ký mac bằng key1. Mã giao dịch có dạng {@code yyMMdd_<bookingId>}.
     *
     * @param amount số tiền (VND)
     * @param orderInfo mô tả đơn hàng; {@code null} thì dùng nội dung mặc định
     * @param bookingId mã booking
     * @return map gồm {@code paymentUrl} và {@code appTransId}
     * @throws IllegalStateException khi ZaloPay lỗi hoặc không trả về {@code order_url}
     */
    public Map<String, String> createPaymentUrl(long amount, String orderInfo, String bookingId) {
        String appTime = String.valueOf(System.currentTimeMillis());
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMdd");
        String appTransId = sdf.format(new Date()) + "_" + (bookingId != null ? bookingId : System.currentTimeMillis());
        String appUser = "haloticket_user";
        String embedData = String.format("{\"redirecturl\": \"%s?bookingId=%s&provider=zalopay\"}", zaloPayConfig.getRedirectUrl(), bookingId);
        String item = "[{\"itemname\":\"HaloTicket Booking\",\"itemprice\":" + amount + ",\"itemquantity\":1}]";
        String description = orderInfo != null ? orderInfo : "Thanh toan don hang HaloTicket " + appTransId;

        String rawData = String.format("%s|%s|%s|%d|%s|%s|%s",
                zaloPayConfig.getAppId(),
                appTransId,
                appUser,
                amount,
                appTime,
                embedData,
                item
        );

        String mac = HmacUtil.hmacSHA256(zaloPayConfig.getKey1(), rawData);

        MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("app_id", zaloPayConfig.getAppId());
        requestBody.add("app_user", appUser);
        requestBody.add("app_time", appTime);
        requestBody.add("amount", String.valueOf(amount));
        requestBody.add("app_trans_id", appTransId);
        requestBody.add("embed_data", embedData);
        requestBody.add("item", item);
        requestBody.add("description", description);
        requestBody.add("bank_code", "");
        requestBody.add("mac", mac);

        Map<String, String> result = new HashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    zaloPayConfig.getEndpoint(),
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                String orderUrl = (String) body.get("order_url");
                if (orderUrl != null) {
                    result.put("paymentUrl", orderUrl);
                    result.put("appTransId", appTransId);
                    log.info("ZaloPay Payment URL created for transId {}: {}", appTransId, orderUrl);
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Call to ZaloPay API endpoint failed or returned sandbox fallback", e);
        }

        throw new IllegalStateException("ZaloPay did not create a payment URL");
    }

    /**
     * Kiểm tra mac của callback ZaloPay bằng key2 và so sánh không phân biệt hoa thường.
     *
     * @param data chuỗi dữ liệu callback
     * @param requestMac mac do ZaloPay gửi
     * @return {@code true} nếu hợp lệ; {@code false} nếu thiếu dữ liệu hoặc mac sai
     */
    public boolean verifyCallback(String data, String requestMac) {
        if (data == null || requestMac == null) return false;
        String mac = HmacUtil.hmacSHA256(zaloPayConfig.getKey2(), data);
        return mac.equalsIgnoreCase(requestMac);
    }
}
