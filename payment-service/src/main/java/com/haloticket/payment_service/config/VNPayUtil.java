package com.haloticket.payment_service.config;

import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Tiện ích cho tích hợp VNPay: ký HMAC-SHA512, lấy IP người gọi, sinh chuỗi số ngẫu nhiên. */
public class VNPayUtil {

    /**
     * Tính HMAC-SHA512 và trả về chuỗi hex chữ thường.
     *
     * @param key khóa bí mật
     * @param data dữ liệu cần ký
     * @return chuỗi hex; null khi khóa hoặc dữ liệu null; chuỗi rỗng khi có lỗi
     */
    public static String hmacSHA512(final String key, final String data) {
        try {
            if (key == null || data == null) {
                return null;
            }
            final Mac hmac512 = Mac.getInstance("HmacSHA512");
            byte[] hmacKeyBytes = key.getBytes(StandardCharsets.UTF_8);
            final SecretKeySpec secretKey = new SecretKeySpec(hmacKeyBytes, "HmacSHA512");
            hmac512.init(secretKey);
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            byte[] result = hmac512.doFinal(dataBytes);
            StringBuilder sb = new StringBuilder(2 * result.length);
            for (byte b : result) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * Lấy địa chỉ IP của client: ưu tiên header {@code X-FORWARDED-FOR}, không có thì dùng địa chỉ kết nối; lỗi thì trả {@code 127.0.0.1}.
     *
     * @param request yêu cầu HTTP
     * @return địa chỉ IP
     */
    public static String getIpAddress(HttpServletRequest request) {
        String ipAddress;
        try {
            ipAddress = request.getHeader("X-FORWARDED-FOR");
            if (ipAddress == null || ipAddress.isEmpty()) {
                ipAddress = request.getRemoteAddr();
            }
        } catch (Exception e) {
            ipAddress = "127.0.0.1";
        }
        return ipAddress;
    }

    /**
     * Sinh chuỗi gồm các chữ số ngẫu nhiên.
     *
     * @param len độ dài chuỗi
     * @return chuỗi số ngẫu nhiên
     */
    public static String getRandomNumber(int len) {
        Random rnd = new Random();
        String chars = "0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
