package com.haloticket.event_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/** Điểm khởi động của event-service: bật Feign client, tác vụ lập lịch và đặt múi giờ mặc định Asia/Ho_Chi_Minh. */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.haloticket.event_service.client")
@EnableScheduling
public class EventServiceApplication {

	/** Đặt múi giờ mặc định là Asia/Ho_Chi_Minh rồi khởi động ứng dụng Spring Boot. */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(EventServiceApplication.class, args);
	}

}
