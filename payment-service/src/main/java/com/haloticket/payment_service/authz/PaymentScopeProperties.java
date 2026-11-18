package com.haloticket.payment_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Thuộc tính cấu hình {@code authz.event-scope}: chứa token nội bộ dùng chung giữa các dịch vụ. */
@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class PaymentScopeProperties {

    private String internalToken = "";

    /**
     * Lấy token nội bộ (chuỗi rỗng nghĩa là chưa cấu hình).
     *
     * @return token nội bộ
     */
    public String getInternalToken() { return internalToken; }
    /**
     * Đặt token nội bộ; giá trị null được đổi thành chuỗi rỗng.
     *
     * @param internalToken token nội bộ
     */
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
}
