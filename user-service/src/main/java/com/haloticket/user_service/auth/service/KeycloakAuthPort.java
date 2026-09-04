package com.haloticket.user_service.auth.service;

public interface KeycloakAuthPort {
    void register(String email, String password);
    boolean matchesEmailAndPhone(String email, String phoneNumber);
    String issueResetToken(String challengeId);
    void resetPassword(String resetToken, String password);
    AuthResponses.Login login(String email, String password);
}
