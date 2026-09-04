package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.config.JwtRoleConverter;
import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final JwtRoleConverter jwtRoleConverter;

    @Override
    public UserResponse createUser(UserCreateRequest request) {
        if (request == null || isBlank(request.getKeycloakId()) || isBlank(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keycloak ID and email are required");
        }
        if (userRepository.findByKeycloakId(request.getKeycloakId()).isPresent()
                || userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User identity already exists");
        }
        User user = User.builder()
                .keycloakId(request.getKeycloakId())
                .email(request.getEmail())
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .gender(request.getGender())
                .roles(request.getRoles())
                .isActive(true)
                .status(UserStatus.ACTIVE)
                .build();
        return toResponse(userRepository.save(user));
    }

    @Override
    public UserResponse getUserByKeycloakId(String keycloakId) {
        return userRepository.findByKeycloakId(keycloakId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Override
    public UserResponse syncCurrentUser(Jwt jwt) {
        String keycloakId = jwt == null ? null : jwt.getSubject();
        String email = jwt == null ? null : jwt.getClaimAsString("email");
        if (isBlank(keycloakId) || isBlank(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Authenticated token must contain subject and email");
        }
        User user = userRepository.findByKeycloakId(keycloakId).orElseGet(() -> {
            Optional<User> emailOwner = userRepository.findByEmail(email);
            if (emailOwner.isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Email already belongs to another identity");
            }
            return User.builder()
                    .keycloakId(keycloakId)
                    .email(email)
                    .isActive(true)
                    .status(UserStatus.ACTIVE)
                    .build();
        });
        user.setEmail(email);
        user.setFullName(resolveName(jwt));
        user.setRoles(jwtRoleConverter.toApplicationRoles(jwt));
        user.setLastLoginAt(LocalDateTime.now());
        if (user.getIsActive() == null) {
            user.setIsActive(true);
        }
        if (user.getStatus() == null) {
            user.setStatus(UserStatus.ACTIVE);
        }
        return toResponse(userRepository.save(user));
    }

    @Override
    public UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request) {
        String keycloakId = jwt == null ? null : jwt.getSubject();
        if (isBlank(keycloakId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authenticated token must contain subject");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile request is required");
        }
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setFullName(request.getFullName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setGender(request.getGender());
        user.setAvatarUrl(request.getAvatarUrl());
        return toResponse(userRepository.save(user));
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl())
                .isActive(user.getIsActive())
                .gender(user.getGender())
                .status(user.getStatus())
                .roles(user.getRoles())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String resolveName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (!isBlank(name)) {
            return name;
        }
        String combined = Stream.of(jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"))
                .filter(value -> !isBlank(value))
                .collect(Collectors.joining(" "));
        return !combined.isBlank() ? combined : jwt.getClaimAsString("preferred_username");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
