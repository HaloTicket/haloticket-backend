package com.haloticket.payment_service.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class VNPayConfig {

    @Value("${vnpay.vnp_Url:https://sandbox.vnpayment.vn/paymentv2/vpcpay.html}")
    private String vnp_Url;

    @Value("${vnpay.vnp_ReturnUrl:http://localhost:5173/booking-success}")
    private String vnp_ReturnUrl;

    @Value("${vnpay.vnp_TmnCode:2QXUYB07}")
    private String vnp_TmnCode;

    @Value("${vnpay.vnp_HashSecret:AXYZ12345678909876543210ABCDEFGH}")
    private String vnp_HashSecret;

    @Value("${vnpay.vnp_ApiUrl:https://sandbox.vnpayment.vn/merchant_webapi/api/transaction}")
    private String vnp_ApiUrl;
}
