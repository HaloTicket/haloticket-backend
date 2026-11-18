package com.haloticket.user_service.service;

import java.util.Optional;

/** Cổng giao tiếp với Keycloak cho các luồng đăng ký, đặt lại mật khẩu, đăng nhập và làm mới token. */
public interface KeycloakAuthPort {
    /** Báo lỗi 409 nếu email đã có tài khoản (trong cơ sở dữ liệu hoặc trên Keycloak). */
    void ensureRegistrationAvailable(String email);
    /** Tạo tài khoản Keycloak với mật khẩu đã chọn, email đã xác minh, và đồng bộ bản ghi người dùng nội bộ. */
    void register(String email, String password);
    /** Tạo tài khoản với mật khẩu ngẫu nhiên không ai biết, email chưa xác minh; chủ tài khoản đặt mật khẩu qua luồng quên mật khẩu. */
    void registerDormant(String email);
    /** Đổi định danh (email hoặc số điện thoại) thành email của tài khoản; rỗng nếu không tìm thấy. */
    Optional<String> resolvePasswordResetEmail(String identifier);
    /** Cấp reset token ngắn hạn sau khi OTP của thử thách đã được xác minh. */
    String issueResetToken(String challengeId);
    /** Đặt mật khẩu mới trên Keycloak bằng reset token còn hạn. */
    void resetPassword(String resetToken, String password);
    /** Đặt mật khẩu mới cho một người dùng Keycloak (đổi mật khẩu khi đã đăng nhập). */
    void changePassword(String keycloakUserId, String newPassword);
    /** Đăng nhập bằng email và mật khẩu, trả về bộ token. */
    AuthResponses.Login login(String email, String password);
    /** Đổi refresh token lấy bộ token mới cho client chỉ định. */
    AuthResponses.Login refresh(String refreshToken, String clientId);
}
