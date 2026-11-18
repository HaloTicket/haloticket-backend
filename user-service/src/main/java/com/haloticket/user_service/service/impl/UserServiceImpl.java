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
import java.util.HashSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.JoinType;

import com.haloticket.user_service.dto.response.AdminUserListItem;
import com.haloticket.user_service.dto.response.AdminUserDetailResponse;
import com.haloticket.user_service.entity.EkycProfile;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ người dùng: tạo, đồng bộ từ JWT Keycloak, cập nhật hồ sơ, đổi mật khẩu,
 * mời người dùng và các truy vấn quản trị (danh sách, chi tiết, gán vai trò).
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final JwtRoleConverter jwtRoleConverter;
    private final KeycloakAuthPort keycloakAuthPort;

    /**
     * Tạo người dùng mới với trạng thái {@code ACTIVE}.
     *
     * @param request thông tin người dùng, bắt buộc có Keycloak ID và email
     * @return người dùng vừa lưu
     * @throws ResponseStatusException HTTP 400 khi thiếu Keycloak ID hoặc email, HTTP 409 khi danh tính đã tồn tại
     */
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

    /**
     * Tra người dùng theo Keycloak ID.
     *
     * @param keycloakId mã chủ thể (sub) trên Keycloak
     * @return thông tin người dùng
     * @throws ResponseStatusException HTTP 404 khi không tìm thấy
     */
    @Override
    public UserResponse getUserByKeycloakId(String keycloakId) {
        return userRepository.findByKeycloakId(keycloakId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * Đồng bộ người dùng hiện tại từ JWT vào cơ sở dữ liệu mỗi lần đăng nhập.
     * Tìm theo Keycloak ID rồi theo email; tạo mới nếu chưa có, gắn lại Keycloak ID cho tài khoản
     * cũ chưa liên kết (ID rỗng hoặc bắt đầu bằng {@code kc-}). Bổ sung avatar, tên và gộp thêm vai trò từ token,
     * đồng thời cập nhật thời điểm đăng nhập cuối. Thiếu email thì dùng {@code <sub>@haloticket.local}.
     *
     * @param jwt token đã xác thực của người dùng
     * @return người dùng sau khi đồng bộ
     * @throws ResponseStatusException HTTP 400 khi token không có sub, HTTP 409 khi email thuộc danh tính khác
     */
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

        // 1. Thử tìm người dùng theo Keycloak ID (và theo email để phát hiện xung đột)
        Optional<User> userByKeycloak = userRepository.findByKeycloakId(keycloakId);
        Optional<User> userByEmail = userRepository.findByEmail(targetEmail);
        User user;

        if (userByKeycloak.isPresent()) {
            user = userByKeycloak.get();
            if (userByEmail.isPresent()) {
                User emailOwner = userByEmail.get();
                boolean isSameUser = (user.getId() != null && user.getId().equals(emailOwner.getId()))
                        || (user.getEmail() != null && user.getEmail().equalsIgnoreCase(targetEmail));
                if (!isSameUser) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Email already belongs to another identity");
                }
            }
        } else if (userByEmail.isPresent()) {
            User existing = userByEmail.get();
            if (isBlank(existing.getKeycloakId()) || existing.getKeycloakId().startsWith("kc-") || keycloakId.equals(existing.getKeycloakId())) {
                existing.setKeycloakId(keycloakId);
                user = existing;
            } else {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Email already belongs to another identity");
            }
        } else {
            user = User.builder()
                    .keycloakId(keycloakId)
                    .email(targetEmail)
                    .status(UserStatus.ACTIVE)
                    .build();
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
        if (user.getRoles() == null) {
            user.setRoles(new HashSet<>());
        }
        if (rolesFromJwt != null && !rolesFromJwt.isEmpty()) {
            user.getRoles().addAll(rolesFromJwt);
        }

        user.setLastLoginAt(LocalDateTime.now());
        if (user.getStatus() == null) {
            user.setStatus(UserStatus.ACTIVE);
        }
        return toResponse(userRepository.save(user));
    }

    /**
     * Cập nhật hồ sơ (họ tên, số điện thoại, giới tính, avatar) của người dùng đang đăng nhập.
     *
     * @param jwt token đã xác thực
     * @param request dữ liệu hồ sơ mới; avatar nếu có phải là URL ảnh HTTPS công khai
     * @return hồ sơ sau khi cập nhật
     * @throws ResponseStatusException HTTP 400 khi token thiếu sub, request rỗng hoặc avatar không hợp lệ; HTTP 404 khi không có người dùng
     */
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

    /**
     * Đổi mật khẩu của người dùng hiện tại sau khi xác minh mật khẩu cũ bằng cách thử đăng nhập Keycloak.
     *
     * @param jwt token đã xác thực
     * @param currentPassword mật khẩu hiện tại
     * @param newPassword mật khẩu mới
     * @throws ResponseStatusException HTTP 400 khi token thiếu sub hoặc mật khẩu hiện tại sai, HTTP 404 khi không có người dùng
     */
    @Override
    public void changePassword(Jwt jwt, String currentPassword, String newPassword) {
        String keycloakId = jwt == null ? null : jwt.getSubject();
        if (isBlank(keycloakId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authenticated token must contain subject");
        }
        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        
        try {
            // Xác minh mật khẩu hiện tại bằng cách thử đăng nhập
            keycloakAuthPort.login(user.getEmail(), currentPassword);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng");
        }
        
        // Đặt mật khẩu mới trên Keycloak
        keycloakAuthPort.changePassword(keycloakId, newPassword);
    }

    /** Chuyển thực thể {@code User} sang {@code UserResponse}; dùng riêng cho {@code getUserById}. */
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

    /**
     * Tra người dùng theo ID nội bộ.
     *
     * @param id ID người dùng trong cơ sở dữ liệu
     * @return thông tin người dùng
     * @throws ResponseStatusException HTTP 404 khi không tìm thấy
     */
    @Override
    public UserResponse getUserById(String id) {
        return userRepository.findById(id)
                .map(this::mapToUserResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /**
     * Mời người dùng theo email. Nếu email đã có tài khoản thì trả về thông tin hiện có;
     * nếu chưa, tạo tài khoản "ngủ" trên Keycloak rồi đọc lại bản ghi vừa được tạo.
     *
     * @param request yêu cầu chứa email (được chuẩn hóa về chữ thường)
     * @return thông tin người được mời, kèm cờ {@code isNewUser}
     * @throws ResponseStatusException HTTP 400 khi email không hợp lệ, HTTP 500 khi tạo tài khoản thất bại
     */
    @Override
    public UserInviteResponse inviteUser(UserInviteRequest request) {
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isEmpty() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }
        Optional<User> existingUser = userRepository.findByEmail(email);
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

        // Tài khoản chưa ai đăng nhập được: người được mời tự đặt mật khẩu qua luồng quên mật khẩu,
        // luồng này gửi mã đến chính hộp thư đó (ở đây không chọn, trả về, ghi log hay gửi email mật khẩu nào)
        try {
            keycloakAuthPort.registerDormant(email);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create user in Keycloak");
        }

        // Đọc lại người dùng vừa tạo
        User newUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "User creation failed"));
        
        // Trả về kết quả
        return UserInviteResponse.builder()
                .userId(newUser.getId())
                .email(newUser.getEmail())
                .name(newUser.getFullName())
                .avatar(newUser.getAvatarUrl())
                .isNewUser(true)
                .build();
    }

    /**
     * Danh sách người dùng cho trang quản trị, có phân trang và lọc động.
     * Giá trị {@code ALL} hoặc {@code null} nghĩa là không lọc; trạng thái hoặc vai trò không hợp lệ bị bỏ qua.
     * Trạng thái eKYC {@code UNVERIFIED} khớp những người chưa có hồ sơ eKYC.
     *
     * @param query từ khóa tìm trong email, họ tên, số điện thoại
     * @param status trạng thái tài khoản
     * @param ekycStatus trạng thái eKYC
     * @param role vai trò
     * @param pageable thông tin phân trang và sắp xếp
     * @return một trang phần tử danh sách quản trị
     */
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

    /**
     * Chi tiết người dùng cho quản trị viên, gồm cả thông tin eKYC, ngân hàng và thuế.
     *
     * @param id ID nội bộ hoặc Keycloak ID của người dùng
     * @return chi tiết người dùng; trường eKYC là {@code null} nếu chưa có hồ sơ, trạng thái mặc định {@code UNVERIFIED}
     * @throws ResponseStatusException HTTP 404 khi không tìm thấy
     */
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

    /**
     * Gán thêm một vai trò cho người dùng; không làm gì nếu người dùng đã có vai trò đó.
     *
     * @param id ID nội bộ của người dùng
     * @param role vai trò cần thêm
     * @throws ResponseStatusException HTTP 404 khi không tìm thấy người dùng
     */
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

    /** Chuyển thực thể {@code User} sang {@code UserResponse} cho các thao tác tạo, tra cứu và đồng bộ. */
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

    /** Suy ra tên hiển thị từ claim {@code name}, rồi {@code given_name} + {@code family_name}, cuối cùng {@code preferred_username}. */
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

    /** Trả về {@code true} khi chuỗi là {@code null} hoặc chỉ gồm khoảng trắng. */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Kiểm tra URL ảnh hợp lệ: dùng HTTPS, có host và không chứa thông tin đăng nhập (userinfo). */
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
