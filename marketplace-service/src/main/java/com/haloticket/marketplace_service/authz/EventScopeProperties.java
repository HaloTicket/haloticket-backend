package com.haloticket.marketplace_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình {@code authz.event-scope} cho việc cache quyền theo sự kiện: thời gian cache thường và thời gian được dùng kết quả cũ cho check-in. */
@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class EventScopeProperties {

    private long cacheSeconds = 30;
    private long staleCheckinSeconds = 300;

    /** Thời gian (giây) một kết quả quyền còn được dùng mà không hỏi lại event-service; mặc định 30. */
    public long getCacheSeconds() { return cacheSeconds; }
    /** Đặt thời gian cache quyền, tính bằng giây. */
    public void setCacheSeconds(long cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    /** Thời gian tối đa (giây) được dùng kết quả cũ cho quyền check-in khi event-service lỗi; mặc định 300. */
    public long getStaleCheckinSeconds() { return staleCheckinSeconds; }
    /** Đặt thời gian tối đa được dùng kết quả cũ cho quyền check-in, tính bằng giây. */
    public void setStaleCheckinSeconds(long staleCheckinSeconds) { this.staleCheckinSeconds = staleCheckinSeconds; }
}
