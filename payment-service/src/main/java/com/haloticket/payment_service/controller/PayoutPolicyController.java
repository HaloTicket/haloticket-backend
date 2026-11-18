package com.haloticket.payment_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Controller giữ chỗ cho API chính sách ứng trước đã bỏ: mọi yêu cầu tới đường dẫn cũ đều nhận HTTP 410. */
@RestController
public class PayoutPolicyController {

    /**
     * Trả HTTP 410 Gone vì chính sách ứng trước không còn được hỗ trợ.
     *
     * @throws ResponseStatusException luôn ném, mã 410
     */
    @RequestMapping({"/api/payouts/admin/policy", "/api/payouts/admin/policy/**"})
    public void retiredAdvancePolicy() {
        throw new ResponseStatusException(HttpStatus.GONE, "Advance payouts are no longer supported");
    }
}
