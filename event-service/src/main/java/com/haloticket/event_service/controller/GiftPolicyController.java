package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.GiftPolicyRequest;
import com.haloticket.event_service.dto.response.GiftPolicyResponse;
import com.haloticket.event_service.service.GiftPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** API chính sách tặng vé của một sự kiện. */
@RestController @RequestMapping("/api/events/{eventId}/gift-policy") @RequiredArgsConstructor
public class GiftPolicyController {
    private final GiftPolicyService service;
    /**
     * Lấy chính sách tặng vé của sự kiện.
     * @param eventId mã sự kiện
     * @return chính sách tặng vé
     */
    @GetMapping public GiftPolicyResponse get(@PathVariable String eventId) { return service.get(eventId); }
    /**
     * Tạo hoặc cập nhật chính sách tặng vé của sự kiện.
     * @param eventId mã sự kiện
     * @param request nội dung chính sách
     * @param authorization header {@code Authorization} của người gọi
     * @return chính sách sau khi lưu
     */
    @PutMapping public GiftPolicyResponse put(@PathVariable String eventId, @RequestBody GiftPolicyRequest request,
            @RequestHeader("Authorization") String authorization) { return service.upsert(eventId, request, authorization); }
}
