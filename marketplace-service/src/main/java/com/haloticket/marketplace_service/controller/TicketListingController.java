package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.dto.*;
import com.haloticket.marketplace_service.service.ResalePurchaseService;
import com.haloticket.marketplace_service.service.TicketListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API cho người dùng về bán lại và tặng vé: đăng tin, tặng, nhận quà, xem tin công khai, giữ chỗ, thanh toán mua vé.
 * Được map cả hai tiền tố {@code /api/marketplace} và {@code /api}.
 */
@RestController
@RequestMapping({"/api/marketplace", "/api"})
@RequiredArgsConstructor
public class TicketListingController {
    private final TicketListingService service;
    private final ResalePurchaseService purchaseService;

    /**
     * Kiểm tra vé có đủ điều kiện được bán lại hay không.
     *
     * @param authorization header {@code Authorization} của người dùng
     * @param ticketId mã vé
     * @return kết quả kiểm tra điều kiện
     */
    @GetMapping("/tickets/{ticketId}/resale-eligibility")
    public ResponseEntity<?> eligibility(@RequestHeader("Authorization") String authorization, @PathVariable String ticketId) {
        return ResponseEntity.ok(service.eligibility(authorization, ticketId));
    }

    /**
     * Bắt đầu saga đăng bán lại một vé; trả HTTP 202 vì xử lý bất đồng bộ.
     *
     * @param key khóa idempotency, có thể bỏ trống
     * @param authorization header {@code Authorization} của người bán
     * @param request thông tin tin bán
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga vừa tạo
     */
    @PostMapping("/listings")
    public ResponseEntity<SagaResponse> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody CreateListingRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.startResale(authentication.getName(), authorization, key, request));
    }

    /**
     * Bắt đầu saga tặng vé cho người khác; trả HTTP 202 vì xử lý bất đồng bộ.
     *
     * @param key khóa idempotency, có thể bỏ trống
     * @param authorization header {@code Authorization} của người tặng
     * @param request thông tin người nhận và vé cần tặng
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga vừa tạo
     */
    @PostMapping("/gifts")
    public ResponseEntity<SagaResponse> gift(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody CreateGiftRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.startGift(authentication.getName(), authorization, key, request));
    }

    /**
     * Người nhận chấp nhận quà tặng.
     *
     * @param sagaId mã saga của lượt tặng
     * @param token token xác nhận trong lời mời, có thể bỏ trống
     * @param authorization header {@code Authorization} của người nhận
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi nhận
     */
    @PostMapping("/gifts/{sagaId}/accept")
    public SagaResponse acceptGift(@PathVariable String sagaId, @RequestParam(required = false) String token,
                                   @RequestHeader("Authorization") String authorization, Authentication authentication) {
        return service.acceptGift(authentication.getName(), authorization, sagaId, token);
    }

    /**
     * Người tặng hủy lượt tặng vé.
     *
     * @param sagaId mã saga của lượt tặng
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi hủy
     */
    @PostMapping("/gifts/{sagaId}/cancel")
    public SagaResponse cancelGift(@PathVariable String sagaId, Authentication authentication) {
        return service.cancelGift(authentication.getName(), sagaId);
    }

    /**
     * Người nhận từ chối quà tặng.
     *
     * @param sagaId mã saga của lượt tặng
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi từ chối
     */
    @PostMapping("/gifts/{sagaId}/decline")
    public SagaResponse declineGift(@PathVariable String sagaId, Authentication authentication) {
        return service.declineGift(authentication.getName(), sagaId);
    }

    /**
     * Lấy lượt tặng đang hoạt động của một vé (nếu có).
     *
     * @param ticketId mã vé
     * @param authentication thông tin xác thực của người gọi
     * @return thông tin lượt tặng dưới dạng map
     */
    @GetMapping("/gifts/by-ticket/{ticketId}")
    public Map<String, Object> getActiveGiftByTicket(@PathVariable String ticketId, Authentication authentication) {
        return service.getActiveGiftByTicket(authentication.getName(), ticketId);
    }

    /**
     * Xem trạng thái một saga của chính người dùng.
     *
     * @param id mã saga
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga
     */
    @GetMapping("/sagas/{id}")
    public SagaResponse saga(@PathVariable String id, Authentication authentication) { return service.getSaga(authentication.getName(), id); }

    /**
     * Liệt kê công khai các tin bán lại đang mở; mã người dùng (nếu đã đăng nhập) được chuyển cho service xử lý riêng.
     *
     * @param pageable thông tin phân trang
     * @param authentication thông tin xác thực, có thể {@code null} khi chưa đăng nhập
     * @return trang tin bán lại
     */
    @GetMapping("/listings")
    public Page<ListingResponse> listings(Pageable pageable, Authentication authentication) {
        return service.publicListings(currentUserId(authentication), pageable);
    }

    /**
     * Xem lời mời tặng vé của một saga.
     *
     * @param sagaId mã saga của lượt tặng
     * @param token token trong lời mời, có thể bỏ trống
     * @param authentication thông tin xác thực của người gọi
     * @return nội dung lời mời dưới dạng map
     */
    @GetMapping("/gifts/{sagaId}/invitation")
    public Map<String, Object> giftInvitation(@PathVariable String sagaId, @RequestParam(required = false) String token, Authentication authentication) {
        return service.giftInvitation(authentication.getName(), sagaId, token);
    }

    /**
     * Liệt kê các lời mời tặng vé gửi tới người dùng hiện tại.
     *
     * @param authentication thông tin xác thực của người gọi
     * @return danh sách lời mời
     */
    @GetMapping("/gifts/mine")
    public List<Map<String, Object>> myGiftInvitations(Authentication authentication) {
        return service.myGiftInvitations(authentication.getName());
    }

    /**
     * Liệt kê công khai các sự kiện có vé đang được bán lại.
     *
     * @return danh sách tóm tắt sự kiện
     */
    @GetMapping("/events")
    public List<ResaleEventSummary> events() { return service.publicEvents(); }

    /**
     * Liệt kê công khai tin bán lại của một sự kiện.
     *
     * @param eventId mã sự kiện
     * @param pageable thông tin phân trang
     * @param authentication thông tin xác thực, có thể {@code null}
     * @return trang tin bán lại
     */
    @GetMapping("/events/{eventId}/listings")
    public Page<ListingResponse> eventListings(@PathVariable String eventId, Pageable pageable, Authentication authentication) {
        return service.publicListingsByEvent(eventId, currentUserId(authentication), pageable);
    }

    /**
     * Xem công khai chi tiết một tin bán lại.
     *
     * @param listingId mã tin bán lại
     * @param authentication thông tin xác thực, có thể {@code null}
     * @return chi tiết tin bán lại
     */
    @GetMapping("/listings/{listingId}")
    public ListingResponse listing(@PathVariable String listingId, Authentication authentication) {
        return service.publicListing(listingId, currentUserId(authentication));
    }

    /**
     * Giữ chỗ vé của một tin bán lại cho người mua; trả HTTP 201.
     *
     * @param listingId mã tin bán lại
     * @param key khóa idempotency, có thể bỏ trống
     * @param request danh sách vé muốn mua, có thể bỏ trống
     * @param authentication thông tin xác thực của người mua
     * @return thông tin giữ chỗ
     */
    @PostMapping("/listings/{listingId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable String listingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ReserveListingRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.reserveListing(authentication.getName(), key, listingId, request == null ? null : request.ticketIds()));
    }

    /** Yêu cầu giữ chỗ: danh sách mã vé cần mua (có thể trống). */
    public record ReserveListingRequest(List<String> ticketIds) {}

    /**
     * Hủy giữ chỗ của người dùng hiện tại trên một tin bán lại.
     *
     * @param listingId mã tin bán lại
     * @param authentication thông tin xác thực của người mua
     * @return HTTP 200 không có thân
     */
    @PostMapping("/listings/{listingId}/cancel-reservation")
    public ResponseEntity<Void> cancelReservation(@PathVariable String listingId, Authentication authentication) {
        service.cancelReservation(currentUserId(authentication), listingId);
        return ResponseEntity.ok().build();
    }

    /**
     * Khởi tạo thanh toán cho một giao dịch mua vé bán lại.
     *
     * @param transactionId mã giao dịch
     * @param authentication thông tin xác thực của người mua
     * @return thông tin khởi tạo thanh toán
     */
    @PostMapping("/transactions/{transactionId}/payment")
    public PaymentInitiationResponse startPayment(@PathVariable String transactionId, Authentication authentication) {
        return purchaseService.startPayment(authentication.getName(), transactionId);
    }

    /**
     * Xem trạng thái giao dịch mua vé bán lại.
     *
     * @param transactionId mã giao dịch
     * @param authentication thông tin xác thực của người mua
     * @return trạng thái giao dịch
     */
    @GetMapping("/transactions/{transactionId}")
    public ResalePurchaseStatusResponse transaction(@PathVariable String transactionId, Authentication authentication) {
        return purchaseService.getStatus(authentication.getName(), transactionId);
    }

    /**
     * Liệt kê các tin bán lại của chính người dùng.
     *
     * @param pageable thông tin phân trang
     * @param authentication thông tin xác thực của người bán
     * @return trang tin bán lại
     */
    @GetMapping("/listings/me")
    public Page<ListingResponse> myListings(Pageable pageable, Authentication authentication) { return service.myListings(authentication.getName(), pageable); }

    /**
     * Người bán hủy tin bán lại của mình.
     *
     * @param listingId mã tin bán lại
     * @param authentication thông tin xác thực của người bán
     * @return trạng thái saga sau khi hủy
     */
    @PostMapping("/listings/{listingId}/cancel")
    public SagaResponse cancelListing(@PathVariable String listingId, Authentication authentication) {
        return service.cancelListing(authentication.getName(), listingId);
    }

    /**
     * Lấy mã người dùng từ thông tin xác thực.
     *
     * @param authentication thông tin xác thực, có thể {@code null}
     * @return tên chủ thể, hoặc {@code null} khi chưa đăng nhập
     */
    private static String currentUserId(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
