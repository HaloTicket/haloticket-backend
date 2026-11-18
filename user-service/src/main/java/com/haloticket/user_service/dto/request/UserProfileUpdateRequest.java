package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Gender;
import lombok.Data;

/** Các trường hồ sơ mà người dùng được tự cập nhật: họ tên, số điện thoại, giới tính, ảnh đại diện. */
@Data
public class UserProfileUpdateRequest {
    private String fullName;
    private String phoneNumber;
    private Gender gender;
    private String avatarUrl;
}
