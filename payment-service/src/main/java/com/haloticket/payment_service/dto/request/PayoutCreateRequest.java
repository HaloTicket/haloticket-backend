package com.haloticket.payment_service.dto.request;

import lombok.Data;

/** Yêu cầu chi trả của ban tổ chức: sự kiện, loại, mã báo giá, phiên bản tài khoản và số tiền yêu cầu. */
@Data
public class PayoutCreateRequest {
    private String eventId;
    private String type;
    private String quoteId;
    private Long accountVersion;
    private Long requestedAmount;
    private Double requestAmount;
    private Double platformFeeRate;
}
