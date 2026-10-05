package com.haloticket.user_service.dto.response;

import com.haloticket.user_service.enums.Gender;
import com.haloticket.user_service.enums.Role;
import com.haloticket.user_service.enums.UserStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
public class AdminUserDetailResponse {
    private String id;
    private String keycloakId;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String avatarUrl;
    private Gender gender;
    private UserStatus status;
    private Set<Role> roles;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    
    // Ekyc info
    private String ekycStatus;
    private String ekycDocumentType;
    private String ekycDocumentNumber;
    private String ekycLegalName;
    private String ekycBirthDate;
    private String ekycAddress;
    private String ekycFrontImageUrl;
    private String ekycBackImageUrl;
    private String ekycFaceVideoUrl;
    private String bankName;
    private String bankAccountName;
    private String bankAccountNumber;
    private String bankBranch;
    private String businessType;
    private String taxName;
    private String taxCode;
    private String taxAddress;
    private LocalDateTime ekycApprovedAt;
}
