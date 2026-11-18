package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.response.TransferSettingsResponse;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.service.EventAccessService;
import com.haloticket.event_service.service.GiftPolicyService;
import com.haloticket.event_service.service.ResalePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** API gộp cài đặt chuyển nhượng vé của sự kiện: chính sách bán lại, chính sách tặng vé và quyền chỉnh sửa của người gọi. */
@RestController @RequestMapping("/api/events/{eventId}/transfer-settings") @RequiredArgsConstructor
public class TransferSettingsController {
    private final ResalePolicyService resaleService;
    private final GiftPolicyService giftService;
    private final EventAccessService access;

    /**
     * Lấy cài đặt chuyển nhượng. Chính sách chưa tồn tại được trả {@code null}; chỉ vai trò {@code OWNER} và {@code ADMIN} được ghi ({@code canWrite}).
     * @param eventId mã sự kiện
     * @param authorization header {@code Authorization} của người gọi
     * @return bộ cài đặt gồm chính sách bán lại, tặng vé và cờ quyền ghi
     */
    @GetMapping
    public TransferSettingsResponse get(@PathVariable String eventId,
            @RequestHeader("Authorization") String authorization) {
        StaffRole role = access.requireRead(eventId, authorization);
        var resale = tryGet(() -> resaleService.get(eventId));
        var gift = tryGet(() -> giftService.get(eventId));
        boolean canWrite = role == StaffRole.OWNER || role == StaffRole.ADMIN;
        return new TransferSettingsResponse(resale, gift, canWrite);
    }

    /**
     * Chạy hàm lấy chính sách và đổi {@code ResponseStatusException} (ví dụ 404) thành {@code null}.
     * @param s hàm lấy chính sách
     * @return chính sách, hoặc {@code null} nếu hàm ném {@code ResponseStatusException}
     */
    private <T> T tryGet(java.util.function.Supplier<T> s) {
        try { return s.get(); } catch (ResponseStatusException ignored) { return null; }
    }
}
