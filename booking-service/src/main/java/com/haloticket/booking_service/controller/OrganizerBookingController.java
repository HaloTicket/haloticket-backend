package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.authz.EventAccessClient;
import com.haloticket.booking_service.client.PaymentServiceClient;
import com.haloticket.booking_service.client.EventServiceClient;
import com.haloticket.booking_service.dto.response.BookingDetailDto;
import com.haloticket.booking_service.entity.Booking;
import com.haloticket.booking_service.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API cho ban tổ chức: xem danh sách đơn của sự kiện và dữ kiện tài chính các đơn đã thanh toán.
 */
@RestController
@RequestMapping("/api/bookings/events/{eventId}/organizer")
@RequiredArgsConstructor
public class OrganizerBookingController {

    private final BookingRepository bookingRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final EventAccessClient eventAccess;
    private final EventServiceClient eventServiceClient;

    /**
     * Liệt kê đơn của sự kiện theo trang (mới nhất trước), có tìm kiếm và lọc suất chiếu; kèm phương thức thanh toán lấy từ payment-service.
     * Lỗi khi gọi payment-service chỉ làm thiếu phương thức thanh toán, không làm hỏng kết quả.
     * @param eventId mã sự kiện
     * @param authorization header Authorization của người gọi, dùng kiểm tra quyền ORDERS_VIEW
     * @param search từ khóa tìm kiếm, bỏ trống để lấy tất cả
     * @param showtimeId mã suất chiếu cần lọc, có thể null
     * @param page số trang bắt đầu từ 0
     * @param size số dòng mỗi trang
     * @return trang chi tiết đơn
     */
    @GetMapping
    public ResponseEntity<Page<BookingDetailDto>> getEventBookings(
            @PathVariable String eventId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String showtimeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        // người gọi phải có quyền ORDERS_VIEW của sự kiện này (event-service trả lời, dùng token của người gọi)
        eventAccess.require(eventId, "ORDERS_VIEW", authorization);
        if (search == null) {
            search = "";
        }
        
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Booking> bookings = bookingRepository.findByEventIdAndSearchAndShowtimeId(eventId, search, showtimeId, pageRequest);
        
        List<String> bookingIds = bookings.getContent().stream()
                .map(Booking::getId)
                .collect(Collectors.toList());
                
        Map<String, String> paymentMethods = Map.of();
        if (!bookingIds.isEmpty()) {
            try {
                paymentMethods = paymentServiceClient.getPaymentMethodsByBookingIds(bookingIds);
            } catch (Exception e) {
                // bỏ qua lỗi (có thể ghi log)
            }
        }
        
        Map<String, String> finalPaymentMethods = paymentMethods;
        Page<BookingDetailDto> dtoPage = bookings.map(b -> BookingDetailDto.builder()
                .id(b.getId())
                .bookingCode(b.getBookingCode())
                .customerId(b.getCustomerId())
                .customerName(b.getCustomerName())
                .customerEmail(b.getCustomerEmail())
                .eventId(b.getEventId())
                .totalAmount(b.getTotalAmount())
                .status(b.getStatus())
                .paymentMethod(finalPaymentMethods.get(b.getId()))
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .items(b.getItems())
                .build());
                
        return ResponseEntity.ok(dtoPage);
    }

    /**
     * Trả mã và tổng tiền các đơn đã thanh toán của sự kiện cho báo cáo tài chính.
     * Chỉ quản trị viên hoặc chủ sự kiện được xem; người khác nhận 404.
     * @param eventId mã sự kiện
     * @param authorization header Authorization của người gọi
     * @return danh sách mã đơn đã thanh toán và tổng số tiền
     */
    @GetMapping("/finance-facts")
    public ResponseEntity<FinanceFactsResponse> getFinanceFacts(@PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        boolean owner = admin || eventServiceClient.getMyEvents(authorization).stream().anyMatch(event -> eventId.equals(event.getId()));
        if (!owner) return ResponseEntity.notFound().build();
        List<BookingRepository.FinanceFact> rows = bookingRepository.findPaidFinanceFactsByEventId(eventId);
        return ResponseEntity.ok(new FinanceFactsResponse(eventId, rows.stream().map(BookingRepository.FinanceFact::getBookingId).toList(),
                rows.stream().mapToLong(row -> row.getAmount() == null ? 0L : Math.round(row.getAmount())).sum()));
    }

    /** Dữ kiện tài chính của sự kiện: các mã đơn đã thanh toán và tổng số tiền. */
    public record FinanceFactsResponse(String eventId, List<String> paidBookingIds, long paidBookingAmount) {}
}
