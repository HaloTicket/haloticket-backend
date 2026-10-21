package com.haloticket.marketplace_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.lang.reflect.Type;

@Configuration
public class FeignConfig {

    @Bean
    public Decoder feignDecoder(ObjectMapper objectMapper) {
        return (Response response, Type type) -> {
            if (response.body() == null || response.status() == 204) {
                return null;
            }
            if (void.class.equals(type) || Void.class.equals(type)) {
                return null;
            }
            if (String.class.equals(type)) {
                String str = Util.toString(response.body().asReader(Util.UTF_8));
                if (str != null && str.startsWith("\"") && str.endsWith("\"") && str.length() >= 2) {
                    str = str.substring(1, str.length() - 1);
                }
                return str;
            }
            try (InputStream is = response.body().asInputStream()) {
                return objectMapper.readValue(is, objectMapper.constructType(type));
            }
        };
    }
}
