package com.haloticket.ticket_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình {@code authz.event-scope}: token nội bộ giữa các dịch vụ và thời gian lưu tạm kết quả kiểm tra quyền. */
@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class EventScopeProperties {

    private String internalToken = "";
    private long cacheSeconds = 30;
    private long staleCheckinSeconds = 300;

    /** Lấy secret dùng cho các lời gọi giữa các dịch vụ; không bao giờ {@code null}. */
    public String getInternalToken() { return internalToken; }
    /** Đặt secret nội bộ; giá trị {@code null} được đổi thành chuỗi rỗng. */
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
    /** Lấy thời gian lưu tạm kết quả quyền (giây). */
    public long getCacheSeconds() { return cacheSeconds; }
    /** Đặt thời gian lưu tạm kết quả quyền (giây). */
    public void setCacheSeconds(long cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    /** Lấy thời gian tối đa (giây) được dùng lại kết quả cũ cho quyền check-in khi event-service lỗi. */
    public long getStaleCheckinSeconds() { return staleCheckinSeconds; }
    /** Đặt thời gian tối đa (giây) được dùng lại kết quả cũ cho quyền check-in. */
    public void setStaleCheckinSeconds(long staleCheckinSeconds) { this.staleCheckinSeconds = staleCheckinSeconds; }
}
