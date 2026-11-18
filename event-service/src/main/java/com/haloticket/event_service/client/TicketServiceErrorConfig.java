package com.haloticket.event_service.client;

import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/** Cấu hình Feign của client ticket-service: mã 422 được đổi thành ngoại lệ nghiệp vụ thay vì {@code FeignException}. */
public class TicketServiceErrorConfig {

    /**
     * Đăng ký bộ giải mã lỗi riêng cho client ticket-service.
     * @return bộ giải mã {@code TicketServiceErrorDecoder}
     */
    @Bean
    ErrorDecoder ticketServiceErrorDecoder() {
        return new TicketServiceErrorDecoder();
    }
}
