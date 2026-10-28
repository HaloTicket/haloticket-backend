package com.haloticket.event_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * member.invitation.*: the invitation flow of event members. v2 (PENDING until the invited person signs in and accepts,
 * with a one-time token) stays off until switched on; with it off an invited person is a member at once, as before.
 */
@Component
@ConfigurationProperties(prefix = "member.invitation")
public class MemberInvitationProperties {

    public static class V2 {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    private V2 v2 = new V2();
    private int ttlHours = 48;
    private int maxPerEventPerDay = 20;
    private int resendMinSeconds = 60;
    private int maxResends = 5;

    public boolean v2On() { return v2.isEnabled(); }

    public V2 getV2() { return v2; }
    public void setV2(V2 v2) { this.v2 = v2; }
    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    public int getMaxPerEventPerDay() { return maxPerEventPerDay; }
    public void setMaxPerEventPerDay(int maxPerEventPerDay) { this.maxPerEventPerDay = maxPerEventPerDay; }
    public int getResendMinSeconds() { return resendMinSeconds; }
    public void setResendMinSeconds(int resendMinSeconds) { this.resendMinSeconds = resendMinSeconds; }
    public int getMaxResends() { return maxResends; }
    public void setMaxResends(int maxResends) { this.maxResends = maxResends; }
}
