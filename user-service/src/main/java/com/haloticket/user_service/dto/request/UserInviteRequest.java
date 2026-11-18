package com.haloticket.user_service.dto.request;

import lombok.Data;

/** Yêu cầu mời người dùng theo email. */
@Data
public class UserInviteRequest {
    private String email;
}
