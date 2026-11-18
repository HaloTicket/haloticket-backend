package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.UserCreateRequest;
import com.haloticket.user_service.dto.request.UserProfileUpdateRequest;
import com.haloticket.user_service.dto.response.UserResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import com.haloticket.user_service.dto.request.UserInviteRequest;
import com.haloticket.user_service.dto.response.UserInviteResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.enums.Role;
import java.util.Set;

/** Nghiệp vụ người dùng: tạo, tra cứu, đồng bộ từ JWT, cập nhật hồ sơ, đổi mật khẩu và các chức năng quản trị. */
public interface UserService {
    /** Tạo người dùng mới ở trạng thái {@code ACTIVE}; báo lỗi nếu thiếu dữ liệu bắt buộc hoặc đã tồn tại. */
    UserResponse createUser(UserCreateRequest request);
    /** Lấy người dùng theo id Keycloak; báo HTTP 404 nếu không có. */
    UserResponse getUserByKeycloakId(String keycloakId);
    /** Đồng bộ người dùng hiện tại từ JWT vào cơ sở dữ liệu (tạo mới hoặc cập nhật) và trả về hồ sơ. */
    UserResponse syncCurrentUser(Jwt jwt);
    /** Cập nhật hồ sơ của người dùng hiện tại. */
    UserResponse updateCurrentUser(Jwt jwt, UserProfileUpdateRequest request);
    /** Lấy người dùng theo id nội bộ. */
    UserResponse getUserById(String id);
    /** Mời người dùng theo email, tạo tài khoản nếu chưa có. */
    UserInviteResponse inviteUser(UserInviteRequest request);
    /** Đổi mật khẩu của người dùng hiện tại sau khi xác nhận mật khẩu cũ. */
    void changePassword(Jwt jwt, String currentPassword, String newPassword);
    
    /** Cấp thêm một vai trò cho người dùng. */
    void addRoleToUser(String id, Role role);
    
    // chức năng quản trị
    /** Liệt kê người dùng cho trang quản trị, lọc theo từ khóa, trạng thái, trạng thái eKYC và vai trò, có phân trang. */
    Page<AdminUserListItem> getAdminUsers(String query, String status, String ekycStatus, String role, Pageable pageable);
    /** Lấy chi tiết người dùng cho quản trị viên, gồm cả thông tin eKYC. */
    AdminUserDetailResponse getAdminUserDetail(String id);
}
