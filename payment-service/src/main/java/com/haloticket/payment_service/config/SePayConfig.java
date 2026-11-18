package com.haloticket.payment_service.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Cấu hình SePay: khóa API, tài khoản ngân hàng nhận tiền, bí mật webhook và token người dùng. */
@Configuration
@Getter
public class SePayConfig {

    @Value("${sepay.api_key:sepay_api_key_demo}")
    private String apiKey;

    @Value("${sepay.account_no:0915020803}")
    private String accountNo;

    @Value("${sepay.bank_name:MBBank}")
    private String bankName;

    @Value("${sepay.account_name:CONG TY TNHH HALOTICKET}")
    private String accountName;

    @Value("${sepay.webhook_secret:halo_sepay_secret_2026}")
    private String webhookSecret;

    @Value("${sepay.user_token:}")
    private String userToken;
}
