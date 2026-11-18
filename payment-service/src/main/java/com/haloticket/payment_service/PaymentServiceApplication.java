package com.haloticket.payment_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Điểm khởi động của payment-service: thanh toán, hoàn tiền và chi trả cho ban tổ chức.
 * Bật Feign client và tác vụ định kỳ; đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh}.
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.haloticket.payment_service.client")
@EnableScheduling
public class PaymentServiceApplication {

	/**
	 * Đặt múi giờ mặc định rồi khởi chạy ứng dụng Spring Boot.
	 *
	 * @param args tham số dòng lệnh
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
