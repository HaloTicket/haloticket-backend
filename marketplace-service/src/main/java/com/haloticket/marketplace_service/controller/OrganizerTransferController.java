package com.haloticket.marketplace_service.controller;

import com.haloticket.marketplace_service.dto.OrganizerGiftResponse;
import com.haloticket.marketplace_service.dto.OrganizerListingResponse;
import com.haloticket.marketplace_service.dto.OrganizerTransferSummaryResponse;
import com.haloticket.marketplace_service.dto.SagaResponse;
import com.haloticket.marketplace_service.enums.ListingStatus;
import com.haloticket.marketplace_service.enums.TransferStatus;
import com.haloticket.marketplace_service.service.OrganizerTransferService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * API cho ban tổ chức theo dõi và quản lý việc chuyển nhượng vé (bán lại, tặng) của một sự kiện.
 * Quyền truy cập được kiểm tra trong {@code OrganizerTransferService}.
 */
@RestController
@RequestMapping("/api/marketplace/organizer/events/{eventId}")
public class OrganizerTransferController {
    private final OrganizerTransferService organizerTransferService;

    /**
     * Tạo controller.
     *
     * @param organizerTransferService service nghiệp vụ chuyển nhượng phía ban tổ chức
     */
    public OrganizerTransferController(OrganizerTransferService organizerTransferService) {
        this.organizerTransferService = organizerTransferService;
    }

    /**
     * Lấy thống kê tổng hợp về chuyển nhượng vé của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @return số liệu tổng hợp
     */
    @GetMapping("/transfer-summary")
    public OrganizerTransferSummaryResponse getSummary(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId) {
        return organizerTransferService.getSummary(authorization, eventId);
    }

    /**
     * Liệt kê tin bán lại của sự kiện, mới nhất trước. Kích thước trang bị giới hạn trong khoảng 1 đến 100.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param page số trang (bắt đầu từ 0)
     * @param size số phần tử mỗi trang
     * @param status lọc theo trạng thái tin, có thể bỏ trống
     * @return trang tin bán lại
     */
    @GetMapping("/listings")
    public Page<OrganizerListingResponse> getListings(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ListingStatus status) {
        int boundedSize = Math.max(1, Math.min(100, size));
        return organizerTransferService.getListings(
                authorization, eventId,
                PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                status
        );
    }

    /**
     * Liệt kê các lượt tặng vé của sự kiện, mới nhất trước. Kích thước trang bị giới hạn trong khoảng 1 đến 100.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param page số trang (bắt đầu từ 0)
     * @param size số phần tử mỗi trang
     * @param status lọc theo trạng thái chuyển nhượng, có thể bỏ trống
     * @return trang lượt tặng vé
     */
    @GetMapping("/gifts")
    public Page<OrganizerGiftResponse> getGifts(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) TransferStatus status) {
        int boundedSize = Math.max(1, Math.min(100, size));
        return organizerTransferService.getGifts(
                authorization, eventId,
                PageRequest.of(page, boundedSize, Sort.by(Sort.Direction.DESC, "createdAt")),
                status
        );
    }

    /**
     * Xem chi tiết một tin bán lại của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @return chi tiết tin bán lại
     */
    @GetMapping("/listings/{listingId}")
    public OrganizerListingResponse getListingDetail(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @PathVariable String listingId) {
        return organizerTransferService.getListingDetail(authorization, eventId, listingId);
    }

    /**
     * Xem chi tiết một lượt tặng vé của sự kiện.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param sagaId mã saga của lượt tặng
     * @return chi tiết lượt tặng
     */
    @GetMapping("/gifts/{sagaId}")
    public OrganizerGiftResponse getGiftDetail(
            @RequestHeader("Authorization") String authorization,
            @PathVariable String eventId,
            @PathVariable String sagaId) {
        return organizerTransferService.getGiftDetail(authorization, eventId, sagaId);
    }

    /**
     * Duyệt hoặc từ chối một tin bán lại đang chờ duyệt. Trả HTTP 400 {@code MODERATION_ACTION_REQUIRED} khi thiếu hành động.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @param request hành động ({@code APPROVE} hoặc {@code REJECT}) và lý do
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi xử lý
     */
    @PostMapping("/listings/{listingId}/moderate")
    public SagaResponse moderateListing(@RequestHeader("Authorization") String authorization,
                                         @PathVariable String eventId, @PathVariable String listingId,
                                         @RequestBody ModerationRequest request, Authentication authentication) {
        if (request.action() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MODERATION_ACTION_REQUIRED");
        return organizerTransferService.moderateListing(authorization, authentication.getName(), eventId, listingId,
                request.action() == ModerationAction.APPROVE, request.reason());
    }

    /**
     * Ban tổ chức hủy một tin bán lại.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param listingId mã tin bán lại
     * @param request lý do hủy, có thể bỏ trống
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi hủy
     */
    @PostMapping("/listings/{listingId}/cancel")
    public SagaResponse cancelListing(@RequestHeader("Authorization") String authorization,
                                      @PathVariable String eventId, @PathVariable String listingId,
                                      @RequestBody(required = false) CancellationRequest request, Authentication authentication) {
        return organizerTransferService.cancelListing(authorization, authentication.getName(), eventId, listingId,
                request == null ? null : request.reason());
    }

    /**
     * Ban tổ chức hủy một lượt tặng vé.
     *
     * @param authorization header {@code Authorization} của ban tổ chức
     * @param eventId mã sự kiện
     * @param sagaId mã saga của lượt tặng
     * @param request lý do hủy, có thể bỏ trống
     * @param authentication thông tin xác thực của người gọi
     * @return trạng thái saga sau khi hủy
     */
    @PostMapping("/gifts/{sagaId}/cancel")
    public SagaResponse cancelGift(@RequestHeader("Authorization") String authorization,
                                   @PathVariable String eventId, @PathVariable String sagaId,
                                   @RequestBody(required = false) CancellationRequest request, Authentication authentication) {
        return organizerTransferService.cancelGift(authorization, authentication.getName(), eventId, sagaId,
                request == null ? null : request.reason());
    }

    /** Hành động kiểm duyệt tin bán lại: duyệt hoặc từ chối. */
    public enum ModerationAction { APPROVE, REJECT }
    /** Nội dung yêu cầu kiểm duyệt: hành động và lý do. */
    public record ModerationRequest(ModerationAction action, String reason) {}
    /** Nội dung yêu cầu hủy: lý do hủy. */
    public record CancellationRequest(String reason) {}
}
