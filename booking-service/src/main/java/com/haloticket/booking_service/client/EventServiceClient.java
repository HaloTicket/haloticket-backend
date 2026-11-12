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

@FeignClient(name = "event-service", configuration = InternalTokenFeignConfig.class)
public interface EventServiceClient {
    @GetMapping("/api/events/{id}")
    EventResponse getEventById(@PathVariable("id") String id);

    @GetMapping("/api/events/{id}/members/my-access")
    EventAccessView getMyAccess(@PathVariable("id") String id, @RequestHeader("Authorization") String authorization);

    @GetMapping("/api/events/{id}/vouchers")
    List<VoucherResponse> getVouchersByEventId(@PathVariable("id") String id);

    @PostMapping("/api/events/vouchers/{voucherId}/use")
    VoucherResponse useVoucher(@PathVariable("voucherId") String voucherId, @RequestParam("count") int count);
}
