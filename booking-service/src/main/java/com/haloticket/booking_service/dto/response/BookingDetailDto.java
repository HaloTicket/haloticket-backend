package com.haloticket.booking_service.dto.response;

import com.haloticket.booking_service.entity.BookingItem;
import com.haloticket.booking_service.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** Chi tiết đơn dành cho ban tổ chức: thông tin khách, tổng tiền, trạng thái, phương thức thanh toán và các dòng vé. */
@Data
@Builder
public class BookingDetailDto {
    private String id;
    private String bookingCode;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String eventId;
    private Double totalAmount;
    private BookingStatus status;
    private String paymentMethod;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<BookingItem> items;
}
