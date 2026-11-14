package com.haloticket.user_service.controller;

import com.haloticket.user_service.dto.request.AdminUserCreateRequest;
import com.haloticket.user_service.dto.response.AdminAuditItem;
import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserStatsResponse;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.service.AdminUserManagementService;
import com.haloticket.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/users/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final AdminUserManagementService management;

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

    /** Head counts by role and status, so the page needs one request instead of one list request per role. */
    @GetMapping("/stats")
    public ResponseEntity<AdminUserStatsResponse> stats() {
        return ResponseEntity.ok(management.stats());
    }

    /** Who changed which account and how, newest first. */
    @GetMapping("/audit")
    public ResponseEntity<Page<AdminAuditItem>> audit(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(management.auditLog(pageRequest));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailResponse> getUserDetail(@PathVariable String id) {
        return ResponseEntity.ok(userService.getAdminUserDetail(id));
    }

    /** Opens an account for somebody else; the person sets a password through the password-reset flow. */
    @PostMapping
    public ResponseEntity<AdminUserListItem> createUser(@AuthenticationPrincipal Jwt admin,
                                                        @RequestBody AdminUserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(management.createUser(admin, request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateUserStatus(@AuthenticationPrincipal Jwt admin, @PathVariable String id,
                                                 @RequestParam UserStatus status,
                                                 @RequestParam(required = false) String reason) {
        management.updateStatus(admin, id, status, reason);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<Void> updateUserRoles(@AuthenticationPrincipal Jwt admin, @PathVariable String id,
                                                @RequestBody Set<Role> roles) {
        management.updateRoles(admin, id, roles);
        return ResponseEntity.ok().build();
    }
}
