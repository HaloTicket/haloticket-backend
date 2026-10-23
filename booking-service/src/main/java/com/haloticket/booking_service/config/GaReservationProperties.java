package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** booking.ga.reservation.*: stock holds for general-admission orders. Off by default. */
@Data
@Component
@ConfigurationProperties(prefix = "booking.ga.reservation")
public class GaReservationProperties {
    private boolean enabled = false;
    private String internalToken = "";
    private int slackSeconds = 60;
}
