package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Role;
import lombok.Data;

import java.util.Set;

/** Dữ liệu quản trị viên nhập để mở tài khoản cho người khác. Không có mật khẩu: người dùng tự đặt qua luồng đặt lại mật khẩu. */
@Data
public class AdminUserCreateRequest {
    private String email;
    private String fullName;
    private String phoneNumber;
    private Set<Role> roles;
}
