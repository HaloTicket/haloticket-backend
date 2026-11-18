package com.haloticket.booking_service.dto.request;

import lombok.Data;
import java.util.List;

/** Yêu cầu tạo đơn đặt vé: người mua, sự kiện, suất chiếu, voucher, ghế đã chọn và các dòng vé. */
@Data
public class BookingCreateRequest {
    private String customerId;
    private String customerEmail;
    private String customerName;
    private String eventId;
    private String showtimeId;

    private String voucherId;
    private Double totalAmount;
    private List<String> seatIds;
    private List<BookingItemRequest> items;
}