package com.haloticket.user_service.dto.request;

/** Yêu cầu mở phiên eKYC: người dùng đã đồng ý điều khoản chưa và phiên bản điều khoản đã đồng ý. */
public record EkycStartSessionRequest(
        boolean consentAccepted,
        String consentVersion
) {}
