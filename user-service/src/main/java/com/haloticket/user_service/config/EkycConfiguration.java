package com.haloticket.user_service.config;

import com.haloticket.user_service.service.EkycDecisionPolicy;
import com.haloticket.user_service.service.IdentityFingerprint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EkycConfiguration {
    @Bean
    EkycDecisionPolicy ekycDecisionPolicy() {
        return new EkycDecisionPolicy();
    }

    @Bean
    IdentityFingerprint identityFingerprint(@Value("${ekyc.fingerprint-secret}") String secret) {
        return new IdentityFingerprint(secret);
    }
}
