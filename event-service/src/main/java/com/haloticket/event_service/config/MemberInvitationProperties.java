package com.haloticket.event_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * member.invitation.*: the invitation flow of event members (PENDING until the invited person signs in and accepts,
 * with a one-time token).
 */
@Component
@ConfigurationProperties(prefix = "member.invitation")
public class MemberInvitationProperties {

    private int ttlHours = 48;
    private int maxPerEventPerDay = 20;
    private int resendMinSeconds = 60;
    private int maxResends = 5;

    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    public int getMaxPerEventPerDay() { return maxPerEventPerDay; }
    public void setMaxPerEventPerDay(int maxPerEventPerDay) { this.maxPerEventPerDay = maxPerEventPerDay; }
    public int getResendMinSeconds() { return resendMinSeconds; }
    public void setResendMinSeconds(int resendMinSeconds) { this.resendMinSeconds = resendMinSeconds; }
    public int getMaxResends() { return maxResends; }
    public void setMaxResends(int maxResends) { this.maxResends = maxResends; }
}
