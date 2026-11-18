package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.BookingResponse;

/** Nghiệp vụ đặt vé: tạo đơn và tra cứu đơn. */
public interface BookingService {
    /**
     * Tạo đơn đặt vé mới.
     * @param request thông tin đơn cần tạo
     * @return đơn vừa tạo
     */
    BookingResponse createBooking(BookingCreateRequest request);
    /**
     * Lấy đơn theo id hoặc mã đơn.
     * @param idOrCode id hoặc mã hiển thị của đơn
     * @return thông tin đơn
     */
    BookingResponse getBookingById(String idOrCode);
    /**
     * Lấy chi tiết đơn theo mã đơn, chỉ khi đơn thuộc về người gọi.
     * @param bookingCode mã hiển thị của đơn
     * @param customerId mã người mua đang gọi
     * @return chi tiết đơn
     */
    BookingDetailResponse getBookingByCode(String bookingCode, String customerId);
}