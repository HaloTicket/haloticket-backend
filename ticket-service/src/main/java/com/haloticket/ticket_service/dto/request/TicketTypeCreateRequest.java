package com.haloticket.ticket_service.dto.request;

import lombok.Data;

/** Yêu cầu tạo hoặc đồng bộ một loại vé: sự kiện, suất diễn, giá, số lượng và giới hạn mua mỗi đơn. */
@Data
public class TicketTypeCreateRequest {
    private String id;
    private String eventId;
    private String showtimeId;
    private String name;
    private Double price;
    private Boolean isFree;
    private Integer totalQuantity;
    private Integer initialQuantity;
    private Integer minPerOrder;
    private Integer maxPerOrder;
    private String description;
}