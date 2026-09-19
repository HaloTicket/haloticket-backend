package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.UserInviteRequest;
import com.haloticket.user_service.dto.response.UserInviteResponse;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.haloticket.user_service.dto.response.UserResponse;

@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    @PostMapping("/invite")
    public ResponseEntity<UserInviteResponse> inviteUser(@RequestBody UserInviteRequest request) {
        return ResponseEntity.ok(userService.inviteUser(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }
}
