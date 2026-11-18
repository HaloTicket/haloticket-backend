package com.haloticket.booking_service.client;

import com.haloticket.booking_service.dto.response.EventResponse;
import com.haloticket.booking_service.dto.response.VoucherResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import com.haloticket.booking_service.authz.EventAccessView;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Feign client gọi event-service: thông tin sự kiện, voucher và quyền của người dùng trong sự kiện.
 */
@FeignClient(name = "event-service", configuration = InternalTokenFeignConfig.class)
public interface EventServiceClient {
    /**
     * @param id mã sự kiện
     * @return thông tin sự kiện
     */
    @GetMapping("/api/events/{id}")
    EventResponse getEventById(@PathVariable("id") String id);

    /**
     * Lấy vai trò và danh sách quyền của người gọi trong một sự kiện.
     *
     * @param id mã sự kiện
     * @param authorization header Authorization của người gọi, chuyển tiếp nguyên trạng
     * @return quyền truy cập của người gọi
     */
    @GetMapping("/api/events/{id}/members/my-access")
    EventAccessView getMyAccess(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    /**
     * @param id mã sự kiện
     * @return danh sách voucher của sự kiện
     */
    @GetMapping("/api/events/{id}/vouchers")
    List<VoucherResponse> getVouchersByEventId(@PathVariable("id") String id);
    
    /**
     * Ghi nhận đã dùng voucher.
     *
     * @param voucherId mã voucher
     * @param count số lượt dùng cần ghi nhận
     * @return voucher sau khi cập nhật
     */
    @PostMapping("/api/events/vouchers/{voucherId}/use")
    VoucherResponse useVoucher(@PathVariable("voucherId") String voucherId, @RequestParam("count") int count);
    
    /**
     * @param authorization header Authorization của người gọi
     * @return các sự kiện mà người gọi quản lý hoặc tham gia
     */
    @GetMapping("/api/events/my-events")
    List<EventResponse> getMyEvents(@RequestHeader("Authorization") String authorization);
}