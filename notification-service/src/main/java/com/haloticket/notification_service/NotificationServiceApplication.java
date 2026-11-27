package com.haloticket.notification_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Điểm khởi động của {@code notification-service}: gửi và quản lý thông báo (email, push), nhận sự kiện từ Kafka.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class NotificationServiceApplication {

	/**
	 * Đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh} rồi khởi chạy ứng dụng Spring Boot.
	 *
	 * @param args tham số dòng lệnh
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

}
