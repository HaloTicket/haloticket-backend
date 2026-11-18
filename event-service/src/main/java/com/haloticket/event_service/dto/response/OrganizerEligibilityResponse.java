package com.haloticket.event_service.dto.response;

/** Kết quả user-service cho biết người dùng có đủ điều kiện làm nhà tổ chức hay không; khi không đủ, {@code reasonCode} nêu lý do. */
public record OrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
