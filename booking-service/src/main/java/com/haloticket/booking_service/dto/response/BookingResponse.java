package com.haloticket.booking_service.dto.response;

import com.haloticket.booking_service.enums.BookingStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class BookingResponse {
    private String id;
    private String bookingCode;
    private String customerId;
    private Double totalAmount;
    private BookingStatus status;
    private LocalDateTime expiredAt;
}