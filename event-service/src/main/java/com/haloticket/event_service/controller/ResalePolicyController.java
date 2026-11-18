package com.haloticket.event_service.controller;

import com.haloticket.event_service.dto.request.ResalePolicyRequest;
import com.haloticket.event_service.dto.response.ResalePolicyResponse;
import com.haloticket.event_service.service.ResalePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** API chính sách bán lại vé của một sự kiện. */
@RestController @RequestMapping("/api/events/{eventId}/resale-policy") @RequiredArgsConstructor
public class ResalePolicyController {
    private final ResalePolicyService service;
    /**
     * Lấy chính sách bán lại vé của sự kiện.
     * @param eventId mã sự kiện
     * @return chính sách bán lại
     */
    @GetMapping public ResalePolicyResponse get(@PathVariable String eventId) { return service.get(eventId); }
    /**
     * Tạo hoặc cập nhật chính sách bán lại vé của sự kiện.
     * @param eventId mã sự kiện
     * @param request nội dung chính sách
     * @param authorization header {@code Authorization} của người gọi
     * @return chính sách sau khi lưu
     */
    @PutMapping public ResalePolicyResponse put(@PathVariable String eventId, @RequestBody ResalePolicyRequest request,
            @RequestHeader("Authorization") String authorization) { return service.upsert(eventId, request, authorization); }
}
