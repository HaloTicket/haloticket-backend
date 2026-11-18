package com.haloticket.booking_service.client;

import com.haloticket.booking_service.dto.response.TicketTypeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client gọi ticket-service để đọc thông tin loại vé.
 */
@FeignClient(name = "ticket-service")
public interface TicketServiceClient {
    /**
     * @param id mã loại vé
     * @return thông tin loại vé
     */
    @GetMapping("/api/tickets/types/{id}")
    TicketTypeDto getTicketTypeById(@PathVariable("id") String id);
}
