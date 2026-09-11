package com.haloticket.user_service.service.impl;

import com.haloticket.user_service.config.JwtRoleConverter;
import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.UserRepository;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
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

        if (isBlank(email) && jwt != null) {
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            if (!isBlank(preferredUsername) && preferredUsername.contains("@")) {
                email = preferredUsername;
            }
        }

        if (isBlank(keycloakId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Authenticated token must contain subject (sub)");
        }
        if (isBlank(email)) {
            email = keycloakId + "@haloticket.local";
        }

        final String targetEmail = email;

        // 1. Try to find user by Keycloak ID
        Optional<User> userByKeycloak = userRepository.findByKeycloakId(keycloakId);
        User user;

        if (userByKeycloak.isPresent()) {
            user = userByKeycloak.get();
        } else {
            // 2. Try to find user by email (e.g. from seed data or prior registration)
            Optional<User> userByEmail = userRepository.findByEmail(targetEmail);
            if (userByEmail.isPresent()) {
                User existing = userByEmail.get();
                // If keycloak_id is null/blank or is a seed dummy id like "kc-...", link keycloakId
                if (isBlank(existing.getKeycloakId()) || existing.getKeycloakId().startsWith("kc-") || keycloakId.equals(existing.getKeycloakId())) {
                    existing.setKeycloakId(keycloakId);
                    user = existing;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Email already belongs to another identity");
                }
            } else {
                user = User.builder()
                        .keycloakId(keycloakId)
                        .email(targetEmail)
                        .isActive(true)
                        .status(UserStatus.ACTIVE)
                        .build();
            }
        }

        // Check if email collides with another user (other than current user)
        Optional<User> emailOwner = userRepository.findByEmail(targetEmail);
        if (emailOwner.isPresent() && user != emailOwner.get()
                && (user.getId() == null || !user.getId().equals(emailOwner.get().getId()))) {
            User existingOwner = emailOwner.get();
            if (isBlank(existingOwner.getKeycloakId()) || existingOwner.getKeycloakId().startsWith("kc-")) {
                existingOwner.setKeycloakId(keycloakId);
                user = existingOwner;
            } else {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Email already belongs to another identity");
            }
        }

        user.setEmail(targetEmail);
        if (isBlank(user.getAvatarUrl()) || user.getAvatarUrl().startsWith("blob:")) {
            String picture = jwt.getClaimAsString("picture");
            user.setAvatarUrl(isPublicImageUrl(picture) ? picture : null);
        }
        if (isBlank(user.getFullName())) {
            String resolvedName = resolveName(jwt);
            if (!isBlank(resolvedName)) {
                user.setFullName(resolvedName);
            }
        }

        Set<Role> rolesFromJwt = jwtRoleConverter.toApplicationRoles(jwt);
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            user.setRoles(rolesFromJwt);
        } else if (rolesFromJwt != null && !rolesFromJwt.isEmpty()) {
            user.getRoles().addAll(rolesFromJwt);
        }

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
        if (!isBlank(request.getAvatarUrl()) && !isPublicImageUrl(request.getAvatarUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Avatar must be an HTTPS image URL");
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

    private boolean isPublicImageUrl(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
