package com.haloticket.user_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Một dòng nhật ký thao tác quản trị trả về cho màn hình quản trị. */
@Data
@Builder
public class AdminAuditItem {
    private String id;
    private String actorEmail;
    private String action;
    private String targetId;
    private String targetEmail;
    private String oldValue;
    private String newValue;
    private String reason;
    private LocalDateTime createdAt;
}
