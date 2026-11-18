package com.haloticket.event_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Thông tin tài khoản ngân hàng nhận tiền và thông tin thuế của nhà tổ chức, gửi sang payment-service. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerPayoutAccountRequest {
    private String organizerUserId;
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxCode;
    private String taxAddress;
}
