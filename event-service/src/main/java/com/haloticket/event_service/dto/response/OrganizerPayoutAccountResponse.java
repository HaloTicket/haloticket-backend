package com.haloticket.event_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Tài khoản nhận tiền và thông tin thuế của nhà tổ chức do payment-service trả về. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizerPayoutAccountResponse {
    private String id;
    private String organizerUserId;
    private String accountHolder;
    private String accountNumber;
    private String bankName;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxCode;
    private String taxAddress;
    private Boolean isVerified;
}
