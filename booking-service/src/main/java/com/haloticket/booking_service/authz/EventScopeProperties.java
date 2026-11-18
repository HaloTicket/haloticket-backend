package com.haloticket.booking_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình {@code authz.event-scope}: token nội bộ và thời gian lưu cache kết quả phân quyền theo sự kiện.
 */
@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class EventScopeProperties {

    /** Bí mật dùng chung, gửi kèm header X-Internal-Token khi gọi payment-service. */
    private String internalToken = "";
    private long cacheSeconds = 30;
    private long staleCheckinSeconds = 300;

    /** @return token nội bộ dùng gọi payment-service */
    public String getInternalToken() { return internalToken; }
    /** @param internalToken token nội bộ; giá trị null được đổi thành chuỗi rỗng */
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
    /** @return số giây một kết quả phân quyền được dùng lại từ cache */
    public long getCacheSeconds() { return cacheSeconds; }
    /** @param cacheSeconds số giây dùng lại kết quả phân quyền từ cache */
    public void setCacheSeconds(long cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    /** @return số giây tối đa được dùng bản cache cũ cho thao tác soát vé khi event-service lỗi */
    public long getStaleCheckinSeconds() { return staleCheckinSeconds; }
    /** @param staleCheckinSeconds số giây tối đa dùng bản cache cũ cho thao tác soát vé */
    public void setStaleCheckinSeconds(long staleCheckinSeconds) { this.staleCheckinSeconds = staleCheckinSeconds; }
}
