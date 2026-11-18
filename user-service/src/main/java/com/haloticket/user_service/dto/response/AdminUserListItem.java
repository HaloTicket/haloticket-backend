package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Set;

/** Một dòng trong danh sách người dùng của trang quản trị. */
@Data
@Builder
public class AdminUserListItem {
    private String id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String avatarUrl;
    private Gender gender;
    private UserStatus status;
    private Set<Role> roles;
    private LocalDateTime createdAt;
    private String ekycStatus;
}
