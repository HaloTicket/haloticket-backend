package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.EkycVerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

/** Trạng thái eKYC của người dùng cùng thông tin giấy tờ đã xác minh. */
public record EkycStatusResponse(
        EkycVerificationStatus status,
        String legalName,
        String documentNumber,
        Instant verifiedAt,
        Long identityVersion,
        String reasonCode,
        String birthDate,
        String address,
        LocalDate documentExpiresOn
) {
    /**
     * Dựng phản hồi rút gọn không có ngày sinh, địa chỉ và ngày hết hạn giấy tờ (các trường đó là {@code null}).
     */
    public EkycStatusResponse(
            EkycVerificationStatus status,
            String legalName,
            String documentNumber,
            Instant verifiedAt,
            Long identityVersion,
            String reasonCode
    ) {
        this(status, legalName, documentNumber, verifiedAt, identityVersion, reasonCode, null, null, null);
    }
}

