package com.haloticket.discovery_server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/** Điểm khởi động của Eureka Server: nơi các service của HaloTicket đăng ký và tra cứu nhau. */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

	/** Khởi động ứng dụng Spring Boot. */
	public static void main(String[] args) {
		SpringApplication.run(DiscoveryServerApplication.class, args);
	}

}
