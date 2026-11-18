package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình {@code booking.late-payment}: thanh toán đến sau khi đơn đã bị hủy hoặc hết hạn. */
@Data
@Component
@ConfigurationProperties(prefix = "booking.late-payment")
public class LatePaymentProperties {
    /** Thời gian (phút) tồn kho được giữ sau khi thanh toán muộn mở lại đơn, cho đến khi vé được phát hành. */
    private int reopenMinutes = 10;
}
