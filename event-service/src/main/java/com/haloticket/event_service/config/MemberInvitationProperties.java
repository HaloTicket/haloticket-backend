package com.haloticket.event_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình {@code member.invitation.*}: luồng mời thành viên sự kiện (trạng thái {@code PENDING} cho tới khi người được mời đăng nhập
 * và chấp nhận bằng token dùng một lần).
 */
@Component
@ConfigurationProperties(prefix = "member.invitation")
public class MemberInvitationProperties {

    private int ttlHours = 48;
    private int maxPerEventPerDay = 20;
    private int resendMinSeconds = 60;
    private int maxResends = 5;

    /** Số giờ lời mời còn hiệu lực. */
    public int getTtlHours() { return ttlHours; }
    /** Đặt số giờ lời mời còn hiệu lực. */
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    /** Số lời mời tối đa cho mỗi sự kiện trong một ngày. */
    public int getMaxPerEventPerDay() { return maxPerEventPerDay; }
    /** Đặt số lời mời tối đa cho mỗi sự kiện trong một ngày. */
    public void setMaxPerEventPerDay(int maxPerEventPerDay) { this.maxPerEventPerDay = maxPerEventPerDay; }
    /** Khoảng cách tối thiểu (giây) giữa hai lần gửi lại lời mời. */
    public int getResendMinSeconds() { return resendMinSeconds; }
    /** Đặt khoảng cách tối thiểu (giây) giữa hai lần gửi lại lời mời. */
    public void setResendMinSeconds(int resendMinSeconds) { this.resendMinSeconds = resendMinSeconds; }
    /** Số lần gửi lại tối đa cho một lời mời. */
    public int getMaxResends() { return maxResends; }
    /** Đặt số lần gửi lại tối đa cho một lời mời. */
    public void setMaxResends(int maxResends) { this.maxResends = maxResends; }
}
