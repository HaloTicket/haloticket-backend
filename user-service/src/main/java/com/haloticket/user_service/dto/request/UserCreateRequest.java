package com.haloticket.user_service.dto.request;

import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import lombok.Data;

import java.util.Set;

/** Dữ liệu tạo người dùng mới: id Keycloak, email, họ tên, số điện thoại, giới tính và các vai trò. */
@Data
public class UserCreateRequest {
    private String keycloakId;
    private String email;
    private String fullName;
    private String phoneNumber;
    private Gender gender;
    private Set<Role> roles;
}