package com.haloticket.media_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Điểm khởi động của {@code media-service}: dịch vụ tải ảnh lên và xóa ảnh trên AWS S3, đăng ký với service discovery.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class MediaServiceApplication {

    /**
     * Khởi chạy ứng dụng Spring Boot.
     *
     * @param args tham số dòng lệnh
     */
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
