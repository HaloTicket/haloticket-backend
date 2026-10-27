package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.UserInviteRequest;
import com.haloticket.user_service.dto.response.UserInviteResponse;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.haloticket.user_service.dto.response.UserResponse;
import com.haloticket.user_service.enums.Role;

@RestController
@RequestMapping("/api/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private static final Logger log = LoggerFactory.getLogger(InternalUserController.class);

    private final UserService userService;

    @PostMapping("/invite")
    public ResponseEntity<UserInviteResponse> inviteUser(@RequestBody UserInviteRequest request) {
        return ResponseEntity.ok(userService.inviteUser(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PutMapping("/{id}/roles/{role}")
    public ResponseEntity<Void> addRoleToUser(@PathVariable String id, @PathVariable String role) {
        Role parsed;
        try {
            parsed = Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (parsed == Role.ADMIN) {
            // callers (event-service) only ever grant ORGANIZER; ADMIN is changed through the admin screen only
            log.warn("Refused internal grant of role ADMIN for user {}", id == null ? "?" : id.substring(0, Math.min(8, id.length())));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROLE_ADMIN_NOT_GRANTABLE_INTERNALLY");
        }
        userService.addRoleToUser(id, parsed);
        return ResponseEntity.ok().build();
    }
}
