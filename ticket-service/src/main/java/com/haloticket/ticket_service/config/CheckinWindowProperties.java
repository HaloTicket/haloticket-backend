package com.haloticket.ticket_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "checkin.window")
public class CheckinWindowProperties {
    private boolean enabled = false;
    private int openBeforeMinutes = 180;
    private int closeAfterEndMinutes = 120;
    private boolean failOpen = true;
}
