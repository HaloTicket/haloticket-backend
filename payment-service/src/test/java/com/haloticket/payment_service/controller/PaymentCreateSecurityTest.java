package com.haloticket.payment_service.controller;

import com.haloticket.payment_service.config.SePayConfig;
import com.haloticket.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Các đường tạo thanh toán phải đăng nhập; IPN và webhook của cổng thanh toán vẫn mở để cổng gọi vào được. */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@AutoConfigureMockMvc
class PaymentCreateSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PaymentService paymentService;
    @MockitoBean SePayConfig sePayConfig;

    @Test
    void createPaymentRecordRequiresLogin() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"b-1\",\"amount\":1000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPaymentUrlsRequireLogin() throws Exception {
        for (String path : new String[] {"vnpay/create-url", "momo/create-url", "zalopay/create-url", "shopeepay/create-url", "vietqr/create-payload"}) {
            mvc.perform(post("/api/payments/" + path).param("amount", "1000").param("bookingId", "b-1"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void gatewayCallbacksStayReachableWithoutLogin() throws Exception {
        when(paymentService.processVnPayIpn(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("RspCode", "00"));
        mvc.perform(get("/api/payments/vnpay/ipn")).andExpect(status().isOk());
    }

    @Test
    void sepayWebhookIsRejectedWhenNoApiKeyIsConfigured() throws Exception {
        when(sePayConfig.getApiKey()).thenReturn("");
        // header "Apikey " (rỗng) từng khớp với khóa rỗng nên ai cũng giả được webhook
        mvc.perform(post("/api/payments/sepay/webhook").header("Authorization", "Apikey ")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sepayWebhookStillAcceptsTheConfiguredKey() throws Exception {
        when(sePayConfig.getApiKey()).thenReturn("secret-key");
        when(paymentService.processSepayWebhook(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("status", "received"));
        mvc.perform(post("/api/payments/sepay/webhook").header("Authorization", "Apikey secret-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/payments/sepay/webhook").header("Authorization", "Apikey wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
