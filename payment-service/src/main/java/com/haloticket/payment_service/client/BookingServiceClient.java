package com.haloticket.payment_service.client;

import com.haloticket.payment_service.dto.response.BookingFinanceFacts;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** Feign client gọi booking-service để lấy chủ đơn và số liệu tài chính của sự kiện. */
@FeignClient(name = "booking-service", path = "/api/bookings")
public interface BookingServiceClient {

    /**
     * Lấy thông tin đơn đặt chỗ (mã đơn và mã khách hàng) để kiểm tra quyền xem thanh toán.
     *
     * @param id mã đơn đặt chỗ
     * @param authorization header Authorization chuyển tiếp từ người gọi
     * @return thông tin chủ đơn
     */
    @GetMapping("/{id}")
    BookingOwner getBooking(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    /**
     * Lấy số liệu tài chính của các đơn thuộc một sự kiện cho ban tổ chức.
     *
     * @param eventId mã sự kiện
     * @param authorization header Authorization chuyển tiếp từ người gọi
     * @return số liệu tài chính của sự kiện
     */
    @GetMapping("/events/{eventId}/organizer/finance-facts")
    BookingFinanceFacts getFinanceFacts(@PathVariable("eventId") String eventId,
            @RequestHeader("Authorization") String authorization);

    /** Phần thông tin tối thiểu của đơn đặt chỗ: mã đơn và mã khách hàng. Bỏ qua các trường lạ khi đọc JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record BookingOwner(String id, String customerId) {}
}
