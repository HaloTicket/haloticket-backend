package com.haloticket.payment_service.dto.request;

import lombok.Data;

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
