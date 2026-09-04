package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
    UserResponse getUserByKeycloakId(String keycloakId);
    UserResponse syncCurrentUser(Jwt jwt);
    UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request);
}
