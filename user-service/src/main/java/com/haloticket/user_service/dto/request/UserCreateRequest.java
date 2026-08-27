package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import lombok.Data;

import java.util.Set;

@Data
public class UserCreateRequest {
    private String keycloakId;
    private String email;
    private String fullName;
    private String phoneNumber;
    private Gender gender;
    private Set<Role> roles;
}