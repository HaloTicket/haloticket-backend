package com.haloticket.api_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * Điểm khởi động của API gateway: cổng vào duy nhất của hệ thống, định tuyến request tới các service phía sau.
 */
@SpringBootApplication
public class ApiGatewayApplication {

	/**
	 * Đặt múi giờ mặc định là {@code Asia/Ho_Chi_Minh} rồi khởi chạy ứng dụng Spring Boot.
	 *
	 * @param args tham số dòng lệnh
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

}
