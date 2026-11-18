package com.haloticket.payment_service.dto.response;

/** Kết quả user-service về việc người dùng đủ điều kiện làm ban tổ chức: cờ đủ điều kiện, mã người dùng, phiên bản định danh và mã lý do. */
public record OrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
