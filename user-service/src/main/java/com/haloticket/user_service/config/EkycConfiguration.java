package com.haloticket.user_service.config;

import com.haloticket.user_service.service.EkycDecisionPolicy;
import com.haloticket.user_service.service.IdentityFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Khai báo các bean eKYC: chính sách quyết định kết quả xác minh và bộ tạo dấu vân tay giấy tờ. */
@Configuration
public class EkycConfiguration {
    /** Chính sách quyết định kết quả eKYC từ các tín hiệu của nhà cung cấp. */
    @Bean
    EkycDecisionPolicy ekycDecisionPolicy() {
        return new EkycDecisionPolicy();
    }

    /**
     * Bộ tạo dấu vân tay giấy tờ với khóa bí mật bắt buộc từ {@code ekyc.fingerprint-secret}.
     *
     * @param secret khóa bí mật HMAC
     */
    @Bean
    IdentityFingerprint identityFingerprint(@Value("${ekyc.fingerprint-secret}") String secret) {
        return new IdentityFingerprint(secret);
    }
}
