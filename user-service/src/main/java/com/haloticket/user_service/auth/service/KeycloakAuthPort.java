package com.haloticket.user_service.auth.service;

import java.util.Optional;

public interface KeycloakAuthPort {
    void register(String email, String password);
    Optional<String> resolvePasswordResetEmail(String identifier);
    String issueResetToken(String challengeId);
    void resetPassword(String resetToken, String password);
    AuthResponses.Login login(String email, String password);
}
