package com.haloticket.user_service.service;

public interface AuthApplicationService {
    AuthOtpChallengeService.CreateResult startRegistration(AuthRequests.RegisterStart request);
    void verifyRegistration(AuthRequests.RegisterVerify request);
    AuthOtpChallengeService.CreateResult resendRegistration(AuthRequests.Challenge request);
    AuthOtpChallengeService.CreateResult startPasswordReset(AuthRequests.PasswordForgot request);
    String verifyPasswordReset(AuthRequests.OtpVerify request);
    AuthOtpChallengeService.CreateResult resendPasswordReset(AuthRequests.Challenge request);
    void resetPassword(AuthRequests.PasswordReset request);
    AuthResponses.Login login(AuthRequests.Login request);
    AuthResponses.Login refresh(AuthRequests.Refresh request);

    class AuthException extends RuntimeException {
        private final String code;
        private final int status;

        public AuthException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public int status() { return status; }
    }
}
