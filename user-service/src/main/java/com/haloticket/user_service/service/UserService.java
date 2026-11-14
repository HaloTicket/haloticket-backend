package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import com.haloticket.user_service.dto.request.UserInviteRequest;
import com.haloticket.user_service.dto.response.UserInviteResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.enums.Role;
import java.util.Set;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
    UserResponse getUserByKeycloakId(String keycloakId);
    UserResponse syncCurrentUser(Jwt jwt);
    UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request);
    UserResponse getUserById(String id);
    UserInviteResponse inviteUser(UserInviteRequest request);
    void changePassword(Jwt jwt, String currentPassword, String newPassword);
    
    void addRoleToUser(String id, Role role);
    
    // admin features
    Page<AdminUserListItem> getAdminUsers(String query, String status, String ekycStatus, String role, Pageable pageable);
    AdminUserDetailResponse getAdminUserDetail(String id);
}
