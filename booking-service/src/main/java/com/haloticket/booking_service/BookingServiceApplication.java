package com.haloticket.booking_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Điểm khởi động của booking-service: quản lý đơn đặt vé, giữ ghế và giữ tồn kho vé. Bật tác vụ định kỳ và Feign client gọi các service khác.
 */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
public class BookingServiceApplication {

	/**
	 * Đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh} rồi khởi chạy ứng dụng Spring Boot.
	 *
	 * @param args tham số dòng lệnh
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(BookingServiceApplication.class, args);
	}

}
