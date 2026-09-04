package com.haloticket.user_service.config;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void declaresJwtDecoderBeanForServletResourceServer() throws NoSuchMethodException {
        Method decoderBean = SecurityConfig.class.getDeclaredMethod("jwtDecoder");

        assertThat(decoderBean.isAnnotationPresent(org.springframework.context.annotation.Bean.class)).isTrue();
    }
}
