package com.haloticket.user_service.service;

import java.util.Optional;

public interface KeycloakAuthPort {
    void ensureRegistrationAvailable(String email);
    void register(String email, String password);
    void registerDormant(String email);
    Optional<String> resolvePasswordResetEmail(String identifier);
    String issueResetToken(String challengeId);
    void resetPassword(String resetToken, String password);
    void changePassword(String keycloakUserId, String newPassword);
    AuthResponses.Login login(String email, String password);
    AuthResponses.Login refresh(String refreshToken, String clientId);
}
