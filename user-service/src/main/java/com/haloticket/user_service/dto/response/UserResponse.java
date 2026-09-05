package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class UserResponse {
    private String id;
    private String keycloakId;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String avatarUrl;
    private Boolean isActive;
    private Gender gender;
    private UserStatus status;
    private Set<Role> roles;
    private LocalDateTime createdAt;
}
