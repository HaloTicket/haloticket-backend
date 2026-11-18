package com.haloticket.event_service.dto.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/** Thông tin cơ bản của người dùng do API nội bộ của user-service trả về. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalUserResponse {
    private String id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String status;
}
