package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.InternalUserResponse;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Client Feign gọi các API nội bộ của user-service: mời người dùng, tra cứu người dùng và gán vai trò. */
@FeignClient(name = "user-service", contextId = "userInternalServiceClient", path = "/api/internal/users")
public interface UserInternalServiceClient {
    /**
     * Mời một người dùng tham gia sự kiện; tạo tài khoản mới nếu email chưa tồn tại.
     * @param request thông tin người được mời
     * @return thông tin người dùng và cờ cho biết có phải tài khoản mới hay không
     */
    @PostMapping("/invite")
    UserInviteResponse inviteUser(@RequestBody EventMemberInviteRequest request);

    /**
     * Tra cứu người dùng theo mã.
     * @param id mã người dùng
     * @return thông tin nội bộ của người dùng
     */
    @GetMapping("/{id}")
    InternalUserResponse getUserById(@PathVariable("id") String id);

    /**
     * Gán thêm một vai trò cho người dùng.
     * @param id mã người dùng
     * @param role tên vai trò cần gán
     */
    @PutMapping("/{id}/roles/{role}")
    void addRoleToUser(@PathVariable("id") String id, @PathVariable("role") String role);
}
