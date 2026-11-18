package com.haloticket.payment_service.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Cấu hình ZaloPay: app id, hai khóa key1 và key2, endpoint và các URL chuyển hướng và IPN. */
@Configuration
@Getter
public class ZaloPayConfig {

    @Value("${zalopay.app_id:2553}")
    private String appId;

    @Value("${zalopay.key1:Pc94W29vbgSMaOverEcMavWlWuChe2ao}")
    private String key1;

    @Value("${zalopay.key2:kLfi2nyWxBo2sStructureW29}")
    private String key2;

    @Value("${zalopay.endpoint:https://sb-openapi.zalopay.vn/v2/create}")
    private String endpoint;

    @Value("${zalopay.redirect_url:http://localhost:5173/payment/callback}")
    private String redirectUrl;

    @Value("${zalopay.ipn_url:http://localhost:8888/api/payments/zalopay/ipn}")
    private String ipnUrl;
}
