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
import com.haloticket.user_service.dto.request.UserInviteRequest;
import com.haloticket.user_service.dto.response.UserInviteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.user_service.service.KeycloakAuthPort;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.List;
import java.util.ArrayList;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;

import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.entity.EkycProfile;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final JwtRoleConverter jwtRoleConverter;
    private final KeycloakAuthPort keycloakAuthPort;

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

        // 1. try to find user by Keycloak ID
        Optional<User> userByKeycloak = userRepository.findByKeycloakId(keycloakId);
        User user;

        if (userByKeycloak.isPresent()) {
            user = userByKeycloak.get();
        } else {
            // 2. try to find user by email 
            Optional<User> userByEmail = userRepository.findByEmail(targetEmail);
            if (userByEmail.isPresent()) {
                User existing = userByEmail.get();
                // link Keycloak ID to existing user account
                existing.setKeycloakId(keycloakId);
                user = existing;
            } else {
                user = User.builder()
                        .keycloakId(keycloakId)
                        .email(targetEmail)
                        .status(UserStatus.ACTIVE)
                        .build();
            }
        }

        // check if email collides with another user (other than current user)
        Optional<User> emailOwner = userRepository.findByEmail(targetEmail);
        if (emailOwner.isPresent() && user != emailOwner.get()
                && (user.getId() == null || !user.getId().equals(emailOwner.get().getId()))) {
            User existingOwner = emailOwner.get();
            existingOwner.setKeycloakId(keycloakId);
            user = existingOwner;
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
        if (user.getStatus() == null) {
            user.setStatus(UserStatus.ACTIVE);
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

    @Override
    public void changePassword(Jwt jwt, String currentPassword, String newPassword) {
        String keycloakId = jwt == null ? null : jwt.getSubject();
        if (isBlank(keycloakId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authenticated token must contain subject");
        }
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        
        try {
            // verify current password by attempting to login
            keycloakAuthPort.login(user.getEmail(), currentPassword);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng");
        }
        
        // update to new password
        keycloakAuthPort.changePassword(keycloakId, newPassword);
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .gender(user.getGender())
                .roles(user.getRoles())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    public UserResponse getUserById(String id) {
        return userRepository.findById(id)
                .map(this::mapToUserResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Override
    public UserInviteResponse inviteUser(UserInviteRequest request) {
        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            return UserInviteResponse.builder()
                    .userId(user.getId())
                    .email(user.getEmail())
                    .name(user.getFullName())
                    .avatar(user.getAvatarUrl())
                    .isNewUser(false)
                    .build();
        }

        // generate a random password
        String generatedPassword = request.getEmail().split("@")[0] + "@123";
        
        // register in Keycloak
        try {
            keycloakAuthPort.register(request.getEmail(), generatedPassword);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create user in Keycloak: " + e.getMessage());
        }

        // fetch the newly created user
        User newUser = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "User creation failed"));
        
        // return response
        return UserInviteResponse.builder()
                .userId(newUser.getId())
                .email(newUser.getEmail())
                .name(newUser.getFullName())
                .avatar(newUser.getAvatarUrl())
                .isNewUser(true)
                .generatedPassword(generatedPassword)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserListItem> getAdminUsers(String query, String status, String ekycStatus, String role, Pageable pageable) {
        Specification<User> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(cb.lower(root.get("phoneNumber")), like)
                ));
            }
            if (status != null && !status.equalsIgnoreCase("ALL")) {
                try {
                    predicates.add(cb.equal(root.get("status"), UserStatus.valueOf(status.toUpperCase())));
                } catch (IllegalArgumentException ignored) {}
            }
            if (ekycStatus != null && !ekycStatus.equalsIgnoreCase("ALL")) {
                var ekycJoin = root.join("ekycProfile", JoinType.LEFT);
                if (ekycStatus.equalsIgnoreCase("UNVERIFIED")) {
                    predicates.add(cb.isNull(ekycJoin.get("status")));
                } else {
                    predicates.add(cb.equal(cb.upper(ekycJoin.get("status").as(String.class)), ekycStatus.toUpperCase()));
                }
            }
            if (role != null && !role.equalsIgnoreCase("ALL")) {
                try {
                    Role roleEnum = Role.valueOf(role.toUpperCase());
                    var roleJoin = root.join("roles", JoinType.INNER);
                    predicates.add(cb.equal(roleJoin, roleEnum));
                    cq.distinct(true);
                } catch (IllegalArgumentException ignored) {}
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return userRepository.findAll(spec, pageable).map(user -> {
            EkycProfile ekyc = user.getEkycProfile();
            return AdminUserListItem.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .phoneNumber(user.getPhoneNumber())
                    .avatarUrl(user.getAvatarUrl())
                    .gender(user.getGender())
                    .status(user.getStatus())
                    .roles(user.getRoles())
                    .createdAt(user.getCreatedAt())
                    .ekycStatus(ekyc != null && ekyc.getStatus() != null ? ekyc.getStatus().name() : "UNVERIFIED")
                    .build();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getAdminUserDetail(String id) {
        User user = userRepository.findById(id)
                .or(() -> userRepository.findByKeycloakId(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        
        EkycProfile ekyc = user.getEkycProfile();
        return AdminUserDetailResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl())
                .gender(user.getGender())
                .status(user.getStatus())
                .roles(user.getRoles())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .ekycStatus(ekyc != null && ekyc.getStatus() != null ? ekyc.getStatus().name() : "UNVERIFIED")
                .ekycDocumentType(ekyc != null && ekyc.getDocumentType() != null ? ekyc.getDocumentType().name() : null)
                .ekycDocumentNumber(ekyc != null ? ekyc.getDocumentNumber() : null)
                .ekycLegalName(ekyc != null ? ekyc.getLegalName() : null)
                .ekycBirthDate(ekyc != null ? ekyc.getBirthDate() : null)
                .ekycAddress(ekyc != null ? ekyc.getAddress() : null)
                .ekycFrontImageUrl(ekyc != null ? ekyc.getFrontImageUrl() : null)
                .ekycBackImageUrl(ekyc != null ? ekyc.getBackImageUrl() : null)
                .ekycFaceVideoUrl(ekyc != null ? ekyc.getFaceVideoUrl() : null)
                .bankName(ekyc != null ? ekyc.getBankName() : null)
                .bankAccountName(ekyc != null ? ekyc.getBankAccountName() : null)
                .bankAccountNumber(ekyc != null ? ekyc.getBankAccountNumber() : null)
                .bankBranch(ekyc != null ? ekyc.getBankBranch() : null)
                .businessType(ekyc != null ? ekyc.getBusinessType() : null)
                .taxName(ekyc != null ? ekyc.getTaxName() : null)
                .taxCode(ekyc != null ? ekyc.getTaxCode() : null)
                .taxAddress(ekyc != null ? ekyc.getTaxAddress() : null)
                .ekycApprovedAt(ekyc != null ? ekyc.getApprovedAt() : null)
                .build();
    }

    @Override
    @Transactional
    public void updateAdminUserStatus(String id, UserStatus status) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setStatus(status);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void updateUserRoles(String id, Set<Role> roles) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setRoles(roles);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void addRoleToUser(String id, Role role) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getRoles() == null) {
            user.setRoles(new java.util.HashSet<>());
        }
        if (!user.getRoles().contains(role)) {
            user.getRoles().add(role);
            userRepository.save(user);
        }
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl())
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
