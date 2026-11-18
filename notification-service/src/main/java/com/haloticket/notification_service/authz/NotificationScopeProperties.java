package com.haloticket.notification_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình {@code authz.event-scope}: chứa token nội bộ dùng chung để các dịch vụ khác gọi API thông báo.
 */
@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class NotificationScopeProperties {

    private String internalToken = "";

    /**
     * Lấy token nội bộ.
     *
     * @return token, chuỗi rỗng nếu chưa cấu hình
     */
    public String getInternalToken() { return internalToken; }
    /**
     * Đặt token nội bộ; giá trị {@code null} được đổi thành chuỗi rỗng.
     *
     * @param internalToken token nội bộ
     */
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
}
