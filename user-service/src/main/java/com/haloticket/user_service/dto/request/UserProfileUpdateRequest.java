package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Gender;
import lombok.Data;

@Data
public class UserProfileUpdateRequest {
    private String fullName;
    private String phoneNumber;
    private Gender gender;
    private String avatarUrl;
}
