package com.haloticket.booking_service.dto.response;

import com.haloticket.booking_service.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/** Thông tin tóm tắt của đơn đặt vé trả về cho client, gồm thời điểm hết hạn giữ chỗ. */
@Data
@Builder
public class BookingResponse {
    private String id;
    private String bookingCode;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String eventId;
    private Double totalAmount;
    private BookingStatus status;
    private LocalDateTime expiredAt;
}