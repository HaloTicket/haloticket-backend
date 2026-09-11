package com.haloticket.payment_service.dto.response;

import com.haloticket.payment_service.enums.PayoutAccountVerificationStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
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
    private PayoutAccountVerificationStatus verificationStatus;
    private Long accountVersion;
    private Long identityVersion;
}
