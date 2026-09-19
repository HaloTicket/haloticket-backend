package com.haloticket.user_service.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserInviteResponse {
    private String userId;
    private String email;
    private String name;
    private String avatar;
    private boolean isNewUser;
    private String generatedPassword;
}
