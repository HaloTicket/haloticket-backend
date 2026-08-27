package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public UserResponse createUser(UserCreateRequest request) {
        return null;
    }

    @Override
    public UserResponse getUserByKeycloakId(String keycloakId) {
        return null;
    }
}
