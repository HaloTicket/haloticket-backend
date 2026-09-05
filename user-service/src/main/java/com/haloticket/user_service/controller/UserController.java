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

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(userService.syncCurrentUser(jwt));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestBody UserProfileUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(jwt, request));
    }
}
