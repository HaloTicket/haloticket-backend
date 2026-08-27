package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.response.UserResponse;

public interface UserService {
    UserResponse createUser(UserCreateRequest request);
    UserResponse getUserByKeycloakId(String keycloakId);
}
