package com.haloticket.payment_service.dto.request;

import lombok.Data;

/**
 * Yêu cầu lưu tài khoản nhận tiền của ban tổ chức: thông tin ngân hàng, loại hình kinh doanh và thông tin thuế.
 * Mã người dùng và phiên bản định danh được controller ghi đè từ eKYC.
 */
@Data
public class OrganizerPayoutAccountRequest {
    private String organizerUserId;
    private Long identityVersion;
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxCode;
    private String taxAddress;
}
