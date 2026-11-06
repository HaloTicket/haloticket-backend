package com.haloticket.booking_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** booking.late-payment.*: a payment that arrives after its booking was cancelled or expired. */
@Data
@Component
@ConfigurationProperties(prefix = "booking.late-payment")
public class LatePaymentProperties {
    /** How long the stock stays held after a late payment reopens it, until the ticket is issued. */
    private int reopenMinutes = 10;
}
