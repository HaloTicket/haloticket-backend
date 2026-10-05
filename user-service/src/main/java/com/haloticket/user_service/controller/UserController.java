package com.haloticket.user_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.service.UserService;
import com.haloticket.user_service.repository.UserRepository;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository users;

    public record GiftRecipient(String userId, String email, String fullName) {}
    public record PublicUser(String userId, String fullName) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    @GetMapping("/gift-recipient")
    public GiftRecipient giftRecipient(@RequestParam String email) {
        var user = users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND"));
        if (!com.haloticket.user_service.enums.UserStatus.ACTIVE.equals(user.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
        return new GiftRecipient(user.getKeycloakId(), user.getEmail(), user.getFullName());
    }

    @GetMapping("/by-keycloak-id")
    public PublicUser byKeycloakId(@RequestParam String id) {
        var user = users.findByKeycloakId(id).orElse(null);
        return new PublicUser(id, user != null ? user.getFullName() : null);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(userService.syncCurrentUser(jwt));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestBody UserProfileUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(jwt, request));
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @RequestBody ChangePasswordRequest request) {
        userService.changePassword(jwt, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
