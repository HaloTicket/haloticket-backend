package com.haloticket.event_service.client;

import lombok.Data;

@Data
public class UserInviteResponse {
    private String userId;
    private String email;
    private String name;
    private String avatar;
    private boolean isNewUser;
    private String generatedPassword;
}
