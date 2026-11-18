package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình {@code booking.ga.reservation}: giữ tồn kho cho đơn vé phổ thông (token nội bộ và thời gian dự phòng). */
@Data
@Component
@ConfigurationProperties(prefix = "booking.ga.reservation")
public class GaReservationProperties {
    private String internalToken = "";
    private int slackSeconds = 60;
}
