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

/** API người dùng: tra cứu người nhận quà, xem và cập nhật hồ sơ, đổi mật khẩu. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository users;

    /** Người nhận quà tặng: id Keycloak, email và họ tên. */
    public record GiftRecipient(String userId, String email, String fullName) {}
    /** Thông tin công khai của người dùng: id Keycloak và họ tên. */
    public record PublicUser(String userId, String fullName) {}
    /** Yêu cầu đổi mật khẩu gồm mật khẩu hiện tại và mật khẩu mới. */
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /**
     * Tìm người nhận quà theo email; chỉ chấp nhận tài khoản đang {@code ACTIVE}.
     *
     * @param email email người nhận (được cắt khoảng trắng và đưa về chữ thường)
     * @return thông tin người nhận
     * @throws ResponseStatusException HTTP 404 {@code RECIPIENT_NOT_FOUND} khi không có hoặc tài khoản không hoạt động
     */
    @GetMapping("/gift-recipient")
    public GiftRecipient giftRecipient(@RequestParam String email) {
        var user = users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND"));
        if (!com.haloticket.user_service.enums.UserStatus.ACTIVE.equals(user.getStatus())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
        return new GiftRecipient(user.getKeycloakId(), user.getEmail(), user.getFullName());
    }

    /**
     * Lấy tên hiển thị theo id Keycloak; họ tên là {@code null} nếu không tìm thấy người dùng.
     *
     * @param id id Keycloak
     * @return thông tin công khai
     */
    @GetMapping("/by-keycloak-id")
    public PublicUser byKeycloakId(@RequestParam String id) {
        var user = users.findByKeycloakId(id).orElse(null);
        return new PublicUser(id, user != null ? user.getFullName() : null);
    }

    /**
     * Trả về hồ sơ người dùng hiện tại, đồng bộ từ JWT vào cơ sở dữ liệu nếu cần.
     *
     * @param jwt token của người dùng hiện tại
     * @return hồ sơ người dùng
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(userService.syncCurrentUser(jwt));
    }

    /**
     * Cập nhật hồ sơ của người dùng hiện tại.
     *
     * @param jwt token của người dùng hiện tại
     * @param request các trường hồ sơ cần cập nhật
     * @return hồ sơ sau khi cập nhật
     */
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestBody UserProfileUpdateRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUser(jwt, request));
    }

    /**
     * Đổi mật khẩu của người dùng hiện tại.
     *
     * @param jwt token của người dùng hiện tại
     * @param request mật khẩu hiện tại và mật khẩu mới
     * @return phản hồi rỗng khi thành công
     */
    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
                                               @RequestBody ChangePasswordRequest request) {
        userService.changePassword(jwt, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
