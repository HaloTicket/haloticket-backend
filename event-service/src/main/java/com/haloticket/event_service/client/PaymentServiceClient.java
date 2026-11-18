package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.event_service.dto.response.OrganizerPayoutAccountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** Client Feign gọi payment-service để quản lý tài khoản nhận tiền (payout) của nhà tổ chức. */
@FeignClient(name = "payment-service", path = "/api/payments")
public interface PaymentServiceClient {

    /**
     * Tạo mới hoặc cập nhật tài khoản nhận tiền của nhà tổ chức.
     * @param authorization header {@code Authorization} chứa JWT của người gọi
     * @param request thông tin tài khoản nhận tiền
     * @return tài khoản nhận tiền sau khi lưu
     */
    @PostMapping("/organizer-payout-account")
    OrganizerPayoutAccountResponse saveOrUpdatePayoutAccount(
            @RequestHeader("Authorization") String authorization,
            @RequestBody OrganizerPayoutAccountRequest request);

    /**
     * Lấy tài khoản nhận tiền của một người dùng.
     * @param authorization header {@code Authorization} chứa JWT của người gọi
     * @param userId mã người dùng là chủ tài khoản
     * @return thông tin tài khoản nhận tiền
     */
    @GetMapping("/organizer-payout-account/{userId}")
    OrganizerPayoutAccountResponse getPayoutAccountByUserId(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("userId") String userId);
}
