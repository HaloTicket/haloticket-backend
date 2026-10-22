package com.haloticket.booking_service.service;

import com.haloticket.booking_service.dto.request.BookingCreateRequest;
import com.haloticket.booking_service.dto.response.BookingDetailResponse;
import com.haloticket.booking_service.dto.response.BookingResponse;

public interface BookingService {
    BookingResponse createBooking(BookingCreateRequest request);
    BookingResponse getBookingById(String idOrCode);
    BookingDetailResponse getBookingByCode(String bookingCode, String customerId);
}