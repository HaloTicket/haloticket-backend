package com.haloticket.payment_service.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Tiện ích băm HMAC dùng khi ký và kiểm tra chữ ký của cổng thanh toán. */
public class HmacUtil {

    /**
     * Tính HMAC-SHA256 và trả về chuỗi hex chữ thường.
     *
     * @param key khóa bí mật
     * @param data dữ liệu cần ký
     * @return chuỗi hex, hoặc chuỗi rỗng khi khóa/dữ liệu null hoặc có lỗi
     */
    public static String hmacSHA256(String key, String data) {
        try {
            if (key == null || data == null) {
                return "";
            }
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] array = sha256Hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte item : array) {
                sb.append(Integer.toHexString((item & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
