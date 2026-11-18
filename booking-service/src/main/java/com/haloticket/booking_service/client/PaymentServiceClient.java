package com.haloticket.booking_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import java.util.Map;

/**
 * Feign client gọi payment-service.
 */
@FeignClient(name = "payment-service", configuration = InternalTokenFeignConfig.class)
public interface PaymentServiceClient {
    /**
     * Tra cứu hàng loạt phương thức thanh toán của các đơn.
     *
     * @param bookingIds danh sách mã đơn
     * @return ánh xạ mã đơn sang phương thức thanh toán
     */
    @PostMapping("/api/payments/methods/bulk")
    Map<String, String> getPaymentMethodsByBookingIds(@RequestBody List<String> bookingIds);
}
