package com.haloticket.user_service.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;

/** Bộ kiểm tra JWT yêu cầu claim {@code aud} chứa đúng audience mà dịch vụ này mong đợi, tránh nhận token cấp cho dịch vụ khác. */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    /**
     * @param expectedAudience audience bắt buộc phải có trong token
     * @throws NullPointerException khi {@code expectedAudience} là null
     */
    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
    }

    /**
     * Kiểm tra token có audience mong đợi hay không.
     *
     * @param token JWT cần kiểm tra
     * @return thành công nếu có audience, ngược lại thất bại với lỗi {@code invalid_token}
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing required audience", null));
    }
}
