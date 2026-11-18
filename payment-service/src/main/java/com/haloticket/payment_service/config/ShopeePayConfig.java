package com.haloticket.payment_service.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Cấu hình ShopeePay: mã đối tác, khóa bí mật, mã merchant và các URL chuyển hướng và IPN. */
@Configuration
@Getter
public class ShopeePayConfig {

    @Value("${shopeepay.partner_id:shopeepay_partner_demo}")
    private String partnerId;

    @Value("${shopeepay.secret_key:shopeepay_secret_demo}")
    private String secretKey;

    @Value("${shopeepay.merchant_id:shopeepay_merchant_demo}")
    private String merchantId;

    @Value("${shopeepay.redirect_url:http://localhost:5173/payment/callback}")
    private String redirectUrl;

    @Value("${shopeepay.ipn_url:http://localhost:8888/api/payments/shopeepay/ipn}")
    private String ipnUrl;
}
