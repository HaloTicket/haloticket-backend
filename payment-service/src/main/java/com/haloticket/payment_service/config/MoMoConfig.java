package com.haloticket.payment_service.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class MoMoConfig {

    @Value("${momo.partner_code:MOMO_DEMO}")
    private String partnerCode;

    @Value("${momo.access_key:momo_access_key_demo}")
    private String accessKey;

    @Value("${momo.secret_key:momo_secret_key_demo}")
    private String secretKey;

    @Value("${momo.endpoint:https://test-payment.momo.vn/v2/gateway/api/create}")
    private String endpoint;

    @Value("${momo.redirect_url:http://localhost:5173/payment/callback}")
    private String redirectUrl;

    @Value("${momo.ipn_url:http://localhost:8888/api/payments/momo/ipn}")
    private String ipnUrl;
}
