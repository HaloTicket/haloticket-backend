package com.haloticket.booking_service.dto.request;

import lombok.Data;

/** Một dòng vé trong yêu cầu đặt vé: loại vé, số lượng và đơn giá. */
@Data
public class BookingItemRequest {
    private String ticketTypeId;
    private Integer quantity;
    private Double unitPrice;
    
    // true với vé vào cổng tự do (không có ghế): số lượng được giữ theo tồn kho chứ không theo ghế.
    private Boolean generalAdmission;
}
