package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import com.haloticket.user_service.enums.Role;

@RestController
@RequestMapping("/api/users/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<AdminUserListItem>> getUsers(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "ALL") String ekycStatus,
            @RequestParam(defaultValue = "ALL") String role,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Sort sortObj = Sort.by(Sort.Direction.DESC, "createdAt");
        if ("oldest".equalsIgnoreCase(sort)) {
            sortObj = Sort.by(Sort.Direction.ASC, "createdAt");
        } else if ("name".equalsIgnoreCase(sort)) {
            sortObj = Sort.by(Sort.Direction.ASC, "fullName");
        }

        PageRequest pageRequest = PageRequest.of(Math.max(0, page - 1), size, sortObj);
        return ResponseEntity.ok(userService.getAdminUsers(query, status, ekycStatus, role, pageRequest));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailResponse> getUserDetail(@PathVariable String id) {
        return ResponseEntity.ok(userService.getAdminUserDetail(id));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateUserStatus(@PathVariable String id, @RequestParam UserStatus status) {
        userService.updateAdminUserStatus(id, status);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<Void> updateUserRoles(@PathVariable String id, @RequestBody Set<Role> roles) {
        userService.updateUserRoles(id, roles);
        return ResponseEntity.ok().build();
    }
}
