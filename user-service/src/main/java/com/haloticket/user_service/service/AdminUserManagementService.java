package com.haloticket.user_service.service;

import com.haloticket.user_service.dto.request.AdminUserCreateRequest;
import com.haloticket.user_service.dto.response.AdminAuditItem;
import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserStatsResponse;
import com.haloticket.user_service.entity.AdminAudit;
import com.haloticket.user_service.entity.EkycProfile;
import com.haloticket.user_service.entity.User;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import com.haloticket.user_service.repository.AdminAuditRepository;
import com.haloticket.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Các thao tác ghi của quản trị viên lên tài khoản người khác: mở tài khoản, khóa hoặc mở khóa, đổi vai trò. Mỗi thay đổi để lại
 * một dòng trong bảng admin_audit. Lỗi là {@code ResponseStatusException} có mã ổn định làm lý do (trang web đổi mã thành văn bản).
 *
 * Tài khoản mới được tạo trong Keycloak với mật khẩu ngẫu nhiên không ai biết (hàm {@code registerDormant} của adapter), nên người
 * dùng đăng nhập bằng cách đặt mật khẩu qua luồng đặt lại mật khẩu, luồng này gửi mã về hộp thư. Service này không tự gửi thư.
 * Vai trò lưu trong bảng user, không ghi sang Keycloak (giống trình sửa vai trò trước đây).
 */
@Service
@RequiredArgsConstructor
public class AdminUserManagementService {

    /** Các vai trò quản trị viên được phép cấp. {@code GUEST} là khách vãng lai, không phải vai trò của tài khoản. */
    public static final Set<Role> ASSIGNABLE_ROLES = EnumSet.of(Role.CUSTOMER, Role.ORGANIZER, Role.STAFF, Role.ADMIN);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern VN_PHONE = Pattern.compile("^0[35789]\\d{8}$");
    private static final int MAX_REASON = 500;

    private final UserRepository userRepository;
    private final KeycloakAuthPort keycloakAuthPort;
    private final AdminAuditRepository auditRepository;

    /**
     * Mở tài khoản cho người khác: kiểm tra email, họ tên, số điện thoại và vai trò, tạo tài khoản "ngủ" trong Keycloak rồi lưu
     * thông tin vào bảng user ở trạng thái {@code ACTIVE} và ghi nhật ký.
     *
     * @param actor JWT của quản trị viên thực hiện
     * @param request dữ liệu tài khoản cần tạo
     * @return dòng người dùng vừa tạo
     * @throws ResponseStatusException HTTP 400 khi dữ liệu sai, 409 khi trùng email hoặc số điện thoại, 502 hoặc 503 khi Keycloak lỗi
     */
    public AdminUserListItem createUser(Jwt actor, AdminUserCreateRequest request) {
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_BODY");
        }
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }
        String fullName = request.getFullName() == null ? "" : request.getFullName().trim();
        if (fullName.length() < 2 || fullName.length() > 100) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_NAME");
        }
        String phone = normalizePhone(request.getPhoneNumber());
        Set<Role> roles = validatedRoles(request.getRoles());

        if (userRepository.findByEmail(email).isPresent()) {
            throw error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
        }
        if (phone != null && userRepository.findFirstByPhoneNumber(phone).isPresent()) {
            throw error(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS");
        }

        try {
            keycloakAuthPort.registerDormant(email);
        } catch (AuthApplicationService.AuthException ex) {
            if (ex.status() == 409) {
                throw error(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
            }
            if (ex.status() == 503) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE");
            }
            throw error(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR");
        } catch (RuntimeException ex) {
            throw error(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> error(HttpStatus.INTERNAL_SERVER_ERROR, "USER_CREATION_FAILED"));
        user.setFullName(fullName);
        user.setPhoneNumber(phone);
        user.setRoles(new HashSet<>(roles));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        audit(actor, AdminAudit.CREATE_USER, user, null, describeRoles(roles), null);
        return toItem(user);
    }

    /**
     * Đổi trạng thái tài khoản và ghi nhật ký; không làm gì nếu trạng thái không đổi.
     * Khi khóa hoặc ngừng tài khoản thì không cho tự khóa mình và không cho khóa quản trị viên hoạt động cuối cùng.
     *
     * @param actor JWT của quản trị viên thực hiện
     * @param id mã người dùng cần đổi
     * @param status trạng thái mới
     * @param reason lý do (tùy chọn, tối đa 500 ký tự)
     * @throws ResponseStatusException HTTP 400, 404 hoặc 409 theo từng lỗi kiểm tra
     */
    @Transactional
    public void updateStatus(Jwt actor, String id, UserStatus status, String reason) {
        if (status == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
        String cleanReason = cleanReason(reason);
        User user = find(id);
        if (user.getStatus() == status) {
            return;
        }
        if (status != UserStatus.ACTIVE) {
            if (isSelf(actor, user)) {
                throw error(HttpStatus.CONFLICT, "CANNOT_LOCK_SELF");
            }
            if (isLastActiveAdmin(user)) {
                throw error(HttpStatus.CONFLICT, "LAST_ADMIN");
            }
        }
        UserStatus before = user.getStatus();
        user.setStatus(status);
        userRepository.save(user);
        audit(actor, AdminAudit.CHANGE_STATUS, user, before == null ? null : before.name(), status.name(), cleanReason);
    }

    /**
     * Thay toàn bộ vai trò của người dùng và ghi nhật ký; không làm gì nếu tập vai trò không đổi.
     * Không cho tự gỡ quyền {@code ADMIN} của mình và không cho gỡ quyền của quản trị viên hoạt động cuối cùng.
     *
     * @param actor JWT của quản trị viên thực hiện
     * @param id mã người dùng cần đổi
     * @param requested tập vai trò mới
     * @throws ResponseStatusException HTTP 400, 404 hoặc 409 theo từng lỗi kiểm tra
     */
    @Transactional
    public void updateRoles(Jwt actor, String id, Set<Role> requested) {
        Set<Role> roles = validatedRoles(requested);
        User user = find(id);
        Set<Role> before = user.getRoles() == null ? Set.of() : user.getRoles();
        if (before.equals(roles)) {
            return;
        }
        boolean losesAdmin = before.contains(Role.ADMIN) && !roles.contains(Role.ADMIN);
        if (losesAdmin) {
            if (isSelf(actor, user)) {
                throw error(HttpStatus.CONFLICT, "CANNOT_REMOVE_OWN_ADMIN");
            }
            if (isLastActiveAdmin(user)) {
                throw error(HttpStatus.CONFLICT, "LAST_ADMIN");
            }
        }
        String oldValue = describeRoles(before);
        user.setRoles(new HashSet<>(roles));
        userRepository.save(user);
        audit(actor, AdminAudit.CHANGE_ROLES, user, oldValue, describeRoles(roles), null);
    }

    /**
     * Thống kê tổng số người dùng, số theo từng vai trò và theo từng trạng thái; mục không có người vẫn được trả với giá trị 0.
     *
     * @return số liệu thống kê
     */
    @Transactional(readOnly = true)
    public AdminUserStatsResponse stats() {
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            byRole.put(role.name(), 0L);
        }
        for (Object[] row : userRepository.countGroupedByRole()) {
            byRole.put(((Role) row[0]).name(), (Long) row[1]);
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (UserStatus status : UserStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (Object[] row : userRepository.countGroupedByStatus()) {
            if (row[0] != null) {
                byStatus.put(((UserStatus) row[0]).name(), (Long) row[1]);
            }
        }
        return AdminUserStatsResponse.builder().total(userRepository.count()).byRole(byRole).byStatus(byStatus).build();
    }

    /**
     * Lấy nhật ký thao tác quản trị theo trang.
     *
     * @param pageable thông tin phân trang và sắp xếp
     * @return trang nhật ký đã đổi sang DTO
     */
    @Transactional(readOnly = true)
    public Page<AdminAuditItem> auditLog(Pageable pageable) {
        return auditRepository.findAll(pageable).map(row -> AdminAuditItem.builder()
                .id(row.getId()).actorEmail(row.getActorEmail()).action(row.getAction())
                .targetId(row.getTargetId()).targetEmail(row.getTargetEmail())
                .oldValue(row.getOldValue()).newValue(row.getNewValue()).reason(row.getReason())
                .createdAt(row.getCreatedAt()).build());
    }

    // ---- helpers

    /** Tìm người dùng theo id, báo HTTP 404 {@code USER_NOT_FOUND} nếu không có. */
    private User find(String id) {
        return userRepository.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    /** Cho biết quản trị viên đang thao tác chính là tài khoản đích (so sánh subject với id Keycloak). */
    private static boolean isSelf(Jwt actor, User target) {
        return actor != null && actor.getSubject() != null && actor.getSubject().equals(target.getKeycloakId());
    }

    /** Đúng khi việc ngừng tài khoản này (hoặc gỡ vai trò admin của nó) sẽ làm hệ thống không còn quản trị viên nào đang hoạt động. */
    private boolean isLastActiveAdmin(User target) {
        boolean activeAdmin = target.getStatus() == UserStatus.ACTIVE
                && target.getRoles() != null && target.getRoles().contains(Role.ADMIN);
        return activeAdmin && userRepository.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE) <= 1;
    }

    /**
     * Kiểm tra tập vai trò không rỗng và chỉ gồm vai trò được phép cấp.
     *
     * @param roles tập vai trò cần kiểm tra
     * @return chính tập đã kiểm tra
     * @throws ResponseStatusException HTTP 400 {@code ROLES_REQUIRED} hoặc {@code INVALID_ROLE}
     */
    private static Set<Role> validatedRoles(Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "ROLES_REQUIRED");
        }
        for (Role role : roles) {
            if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
                throw error(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
            }
        }
        return roles;
    }

    /**
     * Chuẩn hóa số điện thoại di động Việt Nam về dạng 0xxxxxxxxx (chấp nhận tiền tố +84 và 84); trả về {@code null} nếu để trống.
     *
     * @param raw số điện thoại người dùng nhập
     * @return số đã chuẩn hóa hoặc {@code null}
     * @throws ResponseStatusException HTTP 400 {@code INVALID_PHONE} khi sai định dạng
     */
    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("+84")) {
            digits = "0" + digits.substring(3);
        } else if (digits.startsWith("84") && digits.length() == 11) {
            digits = "0" + digits.substring(2);
        }
        if (!VN_PHONE.matcher(digits).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
        return digits;
    }

    /** Cắt khoảng trắng của lý do; trả về {@code null} nếu trống, báo HTTP 400 {@code REASON_TOO_LONG} nếu quá 500 ký tự. */
    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String value = reason.trim();
        if (value.length() > MAX_REASON) {
            throw error(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG");
        }
        return value;
    }

    /** Ghép tên các vai trò theo thứ tự bảng chữ cái, cách nhau dấu phẩy, để ghi vào nhật ký. */
    private static String describeRoles(Set<Role> roles) {
        return roles.stream().sorted(Comparator.comparing(Enum::name)).map(Enum::name).collect(Collectors.joining(","));
    }

    /** Ghi một dòng nhật ký quản trị; nếu không xác định được người thực hiện thì dùng "unknown" làm id Keycloak. */
    private void audit(Jwt actor, String action, User target, String oldValue, String newValue, String reason) {
        auditRepository.save(AdminAudit.builder()
                .actorKeycloakId(actor == null || actor.getSubject() == null ? "unknown" : actor.getSubject())
                .actorEmail(actor == null ? null : actor.getClaimAsString("email"))
                .action(action).targetId(target.getId()).targetEmail(target.getEmail())
                .oldValue(oldValue).newValue(newValue).reason(reason).build());
    }

    /** Đổi người dùng sang dòng danh sách của trang quản trị; chưa có hồ sơ eKYC thì trạng thái là {@code UNVERIFIED}. */
    private static AdminUserListItem toItem(User user) {
        EkycProfile ekyc = user.getEkycProfile();
        return AdminUserListItem.builder()
                .id(user.getId()).email(user.getEmail()).fullName(user.getFullName()).phoneNumber(user.getPhoneNumber())
                .avatarUrl(user.getAvatarUrl()).gender(user.getGender()).status(user.getStatus()).roles(user.getRoles())
                .createdAt(user.getCreatedAt())
                .ekycStatus(ekyc != null && ekyc.getStatus() != null ? ekyc.getStatus().name() : "UNVERIFIED")
                .build();
    }

    /** Tạo {@code ResponseStatusException} với mã lỗi ổn định làm lý do. */
    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
