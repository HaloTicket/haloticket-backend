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

/** API quản trị người dùng ({@code /api/users/admin}), chỉ vai trò ADMIN được gọi: danh sách, thống kê, nhật ký, chi tiết, tạo tài khoản, đổi trạng thái và vai trò. */
@RestController
@RequestMapping("/api/users/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final AdminUserManagementService management;

    /**
     * Danh sách người dùng có lọc và phân trang. Trang đánh số từ 1; sắp xếp mặc định theo ngày tạo mới nhất,
     * {@code oldest} theo ngày tạo cũ nhất, {@code name} theo họ tên.
     *
     * @param query từ khóa tìm kiếm
     * @param status lọc theo trạng thái ({@code ALL} là tất cả)
     * @param ekycStatus lọc theo trạng thái eKYC
     * @param role lọc theo vai trò
     * @param sort kiểu sắp xếp
     * @param page số trang, bắt đầu từ 1
     * @param size số dòng mỗi trang
     * @return trang người dùng
     */
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

    /** Số lượng người dùng theo vai trò và trạng thái, để trang chỉ cần một yêu cầu thay vì một yêu cầu danh sách cho mỗi vai trò. */
    @GetMapping("/stats")
    public ResponseEntity<AdminUserStatsResponse> stats() {
        return ResponseEntity.ok(management.stats());
    }

    /** Nhật ký quản trị: ai đã đổi tài khoản nào và đổi gì, mới nhất trước; kích thước trang giữ trong khoảng 1 đến 100. */
    @GetMapping("/audit")
    public ResponseEntity<Page<AdminAuditItem>> audit(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page - 1), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(management.auditLog(pageRequest));
    }

    /** Chi tiết một người dùng theo mã. */
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailResponse> getUserDetail(@PathVariable String id) {
        return ResponseEntity.ok(userService.getAdminUserDetail(id));
    }

    /**
     * Mở tài khoản cho người khác; người đó tự đặt mật khẩu qua luồng quên mật khẩu.
     *
     * @param admin JWT của quản trị viên thực hiện
     * @param request thông tin tài khoản mới
     * @return tài khoản vừa tạo với mã 201
     */
    @PostMapping
    public ResponseEntity<AdminUserListItem> createUser(@AuthenticationPrincipal Jwt admin,
                                                        @RequestBody AdminUserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(management.createUser(admin, request));
    }

    /**
     * Đổi trạng thái tài khoản.
     *
     * @param admin JWT của quản trị viên thực hiện
     * @param id mã người dùng
     * @param status trạng thái mới
     * @param reason lý do (không bắt buộc)
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<Void> updateUserStatus(@AuthenticationPrincipal Jwt admin, @PathVariable String id,
                                                 @RequestParam UserStatus status,
                                                 @RequestParam(required = false) String reason) {
        management.updateStatus(admin, id, status, reason);
        return ResponseEntity.ok().build();
    }

    /**
     * Thay tập vai trò của người dùng.
     *
     * @param admin JWT của quản trị viên thực hiện
     * @param id mã người dùng
     * @param roles tập vai trò mới
     */
    @PutMapping("/{id}/roles")
    public ResponseEntity<Void> updateUserRoles(@AuthenticationPrincipal Jwt admin, @PathVariable String id,
                                                @RequestBody Set<Role> roles) {
        management.updateRoles(admin, id, roles);
        return ResponseEntity.ok().build();
    }
}
