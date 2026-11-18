package com.haloticket.marketplace_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Điểm khởi động của marketplace-service (chợ bán lại vé và tặng vé). Bật Feign client gọi các dịch vụ khác và các tác vụ chạy theo lịch. */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.haloticket.marketplace_service.client")
@EnableScheduling
public class MarketplaceServiceApplication {
    /** Khởi chạy ứng dụng Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(MarketplaceServiceApplication.class, args);
    }
}
