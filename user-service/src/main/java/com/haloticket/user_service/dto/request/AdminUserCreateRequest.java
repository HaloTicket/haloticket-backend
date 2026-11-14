package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Role;
import lombok.Data;

import java.util.Set;

/** What an administrator supplies to open an account for somebody else. No password: the person sets one through the reset flow. */
@Data
public class AdminUserCreateRequest {
    private String email;
    private String fullName;
    private String phoneNumber;
    private Set<Role> roles;
}
