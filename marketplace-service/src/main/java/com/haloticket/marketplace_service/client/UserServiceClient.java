package com.haloticket.marketplace_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Feign client gọi {@code user-service} ({@code /api/users}) để kiểm tra tư cách bán vé,
 * tìm người nhận quà tặng và lấy thông tin hiển thị của người dùng.
 */
@FeignClient(name = "user-service", path = "/api/users")
public interface UserServiceClient {
    /**
     * Kiểm tra người đang đăng nhập có đủ điều kiện eKYC để đăng bán lại vé hay không.
     *
     * @param authorization header {@code Authorization} (Bearer token) của người gọi
     * @return kết quả đủ điều kiện, kèm mã lý do khi không đủ
     */
    @GetMapping("/me/ekyc/organizer-eligibility")
    ResaleSellerEligibility getResaleEligibility(@RequestHeader("Authorization") String authorization);

    /**
     * Tìm người nhận quà tặng theo email.
     *
     * @param authorization header {@code Authorization} của người tặng
     * @param email email của người nhận
     * @return thông tin người nhận trong {@code user-service}
     */
    @GetMapping("/gift-recipient")
    GiftRecipient getGiftRecipient(@RequestHeader("Authorization") String authorization, @RequestParam("email") String email);

    /**
     * Lấy thông tin người dùng hiện tại từ token.
     *
     * @param authorization header {@code Authorization} của người gọi
     * @return mã Keycloak, email và họ tên của người dùng
     */
    @GetMapping("/me")
    CurrentUser getCurrentUser(@RequestHeader("Authorization") String authorization);

    /**
     * Lấy tên hiển thị công khai của người dùng theo mã Keycloak.
     *
     * @param keycloakId mã người dùng trong Keycloak
     * @return mã người dùng nội bộ và họ tên
     */
    @GetMapping("/by-keycloak-id")
    PublicUser getUserDisplayName(@RequestParam("id") String keycloakId);

    /** Kết quả kiểm tra tư cách bán lại: có đủ điều kiện không, mã người dùng, phiên bản định danh và mã lý do. */
    record ResaleSellerEligibility(boolean eligible, String userId, Long identityVersion, String reasonCode) {}
    /** Thông tin người nhận quà tặng tìm được theo email. */
    record GiftRecipient(String userId, String email, String fullName) {}
    /** Thông tin cơ bản của người dùng đang đăng nhập. */
    record CurrentUser(String keycloakId, String email, String fullName) {}
    /** Thông tin công khai của người dùng (mã và họ tên). */
    record PublicUser(String userId, String fullName) {}
}
