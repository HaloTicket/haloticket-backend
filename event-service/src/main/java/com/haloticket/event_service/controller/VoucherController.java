package com.haloticket.event_service.controller;

import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.service.EventAccessService;
import com.haloticket.event_service.dto.request.VoucherCreateRequest;
import com.haloticket.event_service.dto.request.VoucherUpdateRequest;
import com.haloticket.event_service.dto.response.VoucherResponse;
import com.haloticket.event_service.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.stream.Collectors;

/** API voucher của sự kiện: người mua xem và tra mã, người quản lý voucher tạo, sửa, bật tắt, xóa; booking-service ghi nhận lượt dùng bằng token nội bộ. */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;
    private final EventAccessService access;

    /** Bí mật dùng chung của các service khác (biến môi trường {@code AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN}); để trống nghĩa là không chấp nhận lời gọi nội bộ nào. */
    @Value("${authz.event-scope.internal-token:}")
    private String internalToken = "";

    /**
     * Lấy voucher của sự kiện. Người không có quyền quản lý chỉ thấy voucher đã phát hành và không thấy mã.
     * @param eventId mã sự kiện
     * @return danh sách voucher
     */
    @GetMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> getVouchersByEventId(@PathVariable("eventId") String eventId) {
        List<VoucherResponse> vouchers = voucherService.getVouchersByEventId(eventId);
        if (!mayManageVouchers(eventId)) {
            // danh sách vẫn công khai (trang thanh toán hiển thị các ưu đãi) nhưng mã chỉ dành cho người vận hành,
            // còn voucher đang ở dạng nháp thì chưa phát hành nên không hiển thị
            vouchers = vouchers.stream()
                    .filter(voucher -> !"DRAFT".equalsIgnoreCase(String.valueOf(voucher.getStatus())))
                    .collect(Collectors.toList());
            vouchers.forEach(voucher -> voucher.setCode(null));
        }
        return ResponseEntity.ok(vouchers);
    }

    /**
     * Người mua nhập mã: trả đúng voucher đó (và chỉ voucher đó) nếu đã được phát hành.
     * @param eventId mã sự kiện
     * @param code mã voucher người mua nhập
     * @return voucher tìm được, hoặc 404 nếu không có
     */
    @GetMapping("/{eventId}/vouchers/lookup")
    public ResponseEntity<VoucherResponse> lookupVoucher(
            @PathVariable("eventId") String eventId,
            @RequestParam("code") String code) {
        return voucherService.findReleasedByCode(eventId, code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Kiểm tra người gọi có quyền quản lý voucher của sự kiện hay không.
     * @param eventId mã sự kiện
     * @return {@code true} nếu có quyền {@code VOUCHER_MANAGE}
     */
    private boolean mayManageVouchers(String eventId) {
        try {
            access.requireForRequest(eventId, Permission.VOUCHER_MANAGE);
            return true;
        } catch (ResponseStatusException notAllowed) {
            return false;
        }
    }

    /**
     * Tạo voucher cho sự kiện; cần quyền {@code VOUCHER_MANAGE}.
     * @param eventId mã sự kiện
     * @param request dữ liệu voucher
     * @return danh sách voucher đã tạo với mã 201
     */
    @PostMapping("/{eventId}/vouchers")
    public ResponseEntity<List<VoucherResponse>> createVoucher(
            @PathVariable("eventId") String eventId,
            @RequestBody VoucherCreateRequest request) {
        access.requireForRequest(eventId, Permission.VOUCHER_MANAGE);
        return new ResponseEntity<>(voucherService.createVoucher(eventId, request), HttpStatus.CREATED);
    }

    /**
     * Cập nhật voucher; cần quyền quản lý voucher của sự kiện chứa voucher đó.
     * @param voucherId mã voucher
     * @param request dữ liệu mới
     * @return voucher sau khi sửa
     */
    @PutMapping("/vouchers/{voucherId}")
    public ResponseEntity<VoucherResponse> updateVoucher(
            @PathVariable("voucherId") String voucherId,
            @RequestBody VoucherUpdateRequest request) {
        requireVoucherManager(voucherId);
        return ResponseEntity.ok(voucherService.updateVoucher(voucherId, request));
    }

    /**
     * Bật hoặc tắt voucher; cần quyền quản lý voucher của sự kiện chứa voucher đó.
     * @param voucherId mã voucher
     * @return voucher sau khi đổi trạng thái
     */
    @PatchMapping("/vouchers/{voucherId}/status")
    public ResponseEntity<VoucherResponse> toggleVoucherStatus(@PathVariable("voucherId") String voucherId) {
        requireVoucherManager(voucherId);
        return ResponseEntity.ok(voucherService.toggleVoucherStatus(voucherId));
    }

    /**
     * Ghi nhận lượt dùng voucher; chỉ nhận lời gọi nội bộ mang token đúng.
     * @param voucherId mã voucher
     * @param count số lượt dùng, mặc định 1
     * @param token giá trị header {@code X-Internal-Token}
     * @return voucher sau khi cập nhật số lượt dùng
     * @throws ResponseStatusException 403 {@code INTERNAL_CALLER_ONLY} khi token thiếu hoặc sai
     */
    @PostMapping("/vouchers/{voucherId}/use")
    public ResponseEntity<VoucherResponse> useVoucher(
            @PathVariable("voucherId") String voucherId,
            @RequestParam(name = "count", defaultValue = "1") int count,
            @RequestHeader(name = "X-Internal-Token", required = false) String token) {
        if (!isInternalCaller(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INTERNAL_CALLER_ONLY");
        }
        return ResponseEntity.ok(voucherService.useVoucher(voucherId, count));
    }

    /**
     * So sánh token nội bộ bằng phép so sánh thời gian không đổi.
     * @param token token do người gọi gửi lên
     * @return {@code true} nếu token đã cấu hình, không rỗng và trùng khớp
     */
    private boolean isInternalCaller(String token) {
        if (internalToken == null || internalToken.isBlank() || token == null) return false;
        return java.security.MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Xóa voucher; cần quyền quản lý voucher của sự kiện chứa voucher đó.
     * @param voucherId mã voucher
     * @return 204 không có thân
     */
    @DeleteMapping("/vouchers/{voucherId}")
    public ResponseEntity<Void> deleteVoucher(@PathVariable("voucherId") String voucherId) {
        requireVoucherManager(voucherId);
        voucherService.deleteVoucher(voucherId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Sự kiện của chính voucher quyết định quyền (mã voucher của sự kiện khác không đủ): 404 nếu voucher không tồn tại, 403 nếu không có quyền.
     * @param voucherId mã voucher cần kiểm tra
     */
    private void requireVoucherManager(String voucherId) {
        access.requireForRequest(voucherService.eventIdOf(voucherId), Permission.VOUCHER_MANAGE);
    }
}
