package com.haloticket.api_gateway.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;

/**
 * Bộ kiểm tra claim {@code aud} của JWT: chỉ chấp nhận token có chứa audience mà gateway mong đợi.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    /**
     * @param expectedAudience audience bắt buộc phải có trong token, không được null
     * @throws NullPointerException khi {@code expectedAudience} là null
     */
    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
    }

    /**
     * Kiểm tra danh sách audience của token có chứa giá trị mong đợi hay không.
     *
     * @param token JWT cần kiểm tra
     * @return kết quả thành công, hoặc lỗi {@code invalid_token} khi thiếu audience
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing required audience", null));
    }
}
