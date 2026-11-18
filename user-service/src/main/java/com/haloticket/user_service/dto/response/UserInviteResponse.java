package com.haloticket.user_service.dto.response;

import lombok.Builder;
import lombok.Data;

/** Kết quả mời người dùng: thông tin người được mời và cờ cho biết tài khoản vừa được tạo mới hay không. */
@Data
@Builder
public class UserInviteResponse {
    private String userId;
    private String email;
    private String name;
    private String avatar;
    private boolean isNewUser;
}
