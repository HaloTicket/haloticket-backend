package com.haloticket.user_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/** Điểm khởi chạy của user-service: quản lý tài khoản, xác thực, OTP và xác minh danh tính eKYC. */
@SpringBootApplication
public class UserServiceApplication {

	/**
	 * Đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh} rồi khởi chạy ứng dụng Spring Boot.
	 *
	 * @param args tham số dòng lệnh
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
