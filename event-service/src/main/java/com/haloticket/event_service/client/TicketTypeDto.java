package com.haloticket.event_service.client;

import lombok.Data;

/** Thông tin rút gọn của một loại vé nhận từ ticket-service: mã, tên, giá và tổng số lượng. */
@Data
public class TicketTypeDto {
    private String id;
    private String name;
    private Double price;
    private Integer totalQuantity;
}
