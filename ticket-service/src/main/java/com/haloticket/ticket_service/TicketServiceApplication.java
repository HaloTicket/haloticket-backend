package com.haloticket.ticket_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/** Điểm khởi động của ticket-service: quản lý loại vé, vé đã phát hành, ghế, giữ chỗ tồn kho, check-in và chợ vé bán lại. */
@SpringBootApplication
@EnableScheduling
@EnableFeignClients
public class TicketServiceApplication {

	/** Đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh} rồi chạy ứng dụng Spring Boot. */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(TicketServiceApplication.class, args);
	}

}
