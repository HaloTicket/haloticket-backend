package com.haloticket.event_service.client;

import lombok.Data;

/** Kết quả user-service trả về khi mời người dùng: thông tin người dùng và cờ {@code isNewUser} cho biết tài khoản vừa được tạo hay đã có sẵn. */
@Data
public class UserInviteResponse {
    private String userId;
    private String email;
    private String name;
    private String avatar;
    private boolean isNewUser;
}
