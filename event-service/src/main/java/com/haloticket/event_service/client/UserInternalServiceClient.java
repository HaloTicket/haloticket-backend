package com.haloticket.event_service.client;

import com.haloticket.event_service.dto.request.EventMemberInviteRequest;
import com.haloticket.event_service.dto.response.InternalUserResponse;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service", contextId = "userInternalServiceClient", path = "/api/internal/users")
public interface UserInternalServiceClient {
    @PostMapping("/invite")
    UserInviteResponse inviteUser(@RequestBody EventMemberInviteRequest request);

    @GetMapping("/{id}")
    InternalUserResponse getUserById(@PathVariable("id") String id);

    @PutMapping("/{id}/roles/{role}")
    void addRoleToUser(@PathVariable("id") String id, @PathVariable("role") String role);
}
