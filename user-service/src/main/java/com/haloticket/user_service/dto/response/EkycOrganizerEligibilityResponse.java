package com.haloticket.user_service.dto.response;

/** Kết quả kiểm tra điều kiện eKYC để làm nhà tổ chức: đủ điều kiện hay không, phiên bản định danh và mã lý do. */
public record EkycOrganizerEligibilityResponse(
        boolean eligible,
        String userId,
        Long identityVersion,
        String reasonCode
) {}
