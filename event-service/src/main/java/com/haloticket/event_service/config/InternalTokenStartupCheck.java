package com.haloticket.event_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Token nội bộ ({@code authz.event-scope.internal-token}, biến môi trường {@code AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN}) là thứ các service khác
 * đòi hỏi ở các lệnh gọi nội bộ. Thiếu token thì chúng từ chối event-service; service này vẫn tiếp tục chạy.
 */
@Slf4j
@Component
public class InternalTokenStartupCheck {

    private final String token;

    /**
     * Nhận giá trị token nội bộ từ cấu hình.
     * @param token giá trị token nội bộ; rỗng nếu chưa cấu hình
     */
    public InternalTokenStartupCheck(@Value("${authz.event-scope.internal-token:}") String token) {
        this.token = token;
    }

    /** Khi ứng dụng sẵn sàng, ghi log lỗi nếu chưa cấu hình token nội bộ. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportMissingToken() {
        if (token == null || token.isBlank()) {
            log.error("AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN (authz.event-scope.internal-token) is not set: internal calls from "
                    + "event-service to other services will be refused until it is set. The service keeps running.");
        }
    }
}
