package com.haloticket.booking_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Chuyển JWT thành {@code JwtAuthenticationToken}: gom vai trò từ claim {@code roles} và {@code realm_access.roles},
 * thêm tiền tố {@code ROLE_} và viết hoa nếu chưa có.
 */
@Component
public class BookingJwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {
    /**
     * @param jwt token đã được xác thực chữ ký
     * @return token xác thực kèm các quyền {@code ROLE_*}, tên chủ thể là {@code sub}
     */
    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        List<String> direct = jwt.getClaimAsStringList("roles");
        if (direct != null) roles.addAll(direct);
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> values)
            values.forEach(value -> roles.add(String.valueOf(value)));

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase(Locale.ROOT))
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
