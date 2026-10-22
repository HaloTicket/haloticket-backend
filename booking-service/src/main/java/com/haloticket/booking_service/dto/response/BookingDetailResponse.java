package com.haloticket.booking_service.dto.response;

import com.haloticket.booking_service.enums.BookingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailResponse {
    private String id;
    private String bookingCode;
    private BookingStatus status;
    private String eventId;
    private String showtimeId;
    private Double totalAmount;
    private LocalDateTime createdAt;
    private List<BookingItemDetailResponse> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingItemDetailResponse {
        private String ticketTypeId;
        private String ticketTypeName;
        private Integer quantity;
        private Double unitPrice;
    }
}
