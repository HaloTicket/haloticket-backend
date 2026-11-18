package com.haloticket.booking_service.client;

import com.haloticket.booking_service.dto.response.TicketTypeDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Đọc minPerOrder / maxPerOrder cho OrderLimitValidator. Tách thành client riêng để đặt timeout ngắn riêng. */
@FeignClient(name = "ticket-service", contextId = "ticketOrderLimitClient")
public interface TicketOrderLimitClient {

    /**
     * @param id mã loại vé
     * @return thông tin loại vé, gồm giới hạn số vé mỗi đơn
     */
    @GetMapping("/api/tickets/types/{id}")
    TicketTypeDto getTicketTypeById(@PathVariable("id") String id);
}
