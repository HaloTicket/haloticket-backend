package com.haloticket.event_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.*;

/** Chuyển JWT thành {@code JwtAuthenticationToken} với quyền lấy từ claim {@code roles} và {@code realm_access.roles}. */
@Component
public class JwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {
    /**
     * Gộp vai trò từ hai claim, thêm tiền tố {@code ROLE_} (viết hoa) nếu chưa có rồi tạo authority.
     * @param jwt JWT đã được xác thực
     * @return token xác thực với chủ thể là {@code sub} của JWT
     */
    @Override public JwtAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        List<String> direct = jwt.getClaimAsStringList("roles");
        if (direct != null) roles.addAll(direct);
        Object realm = jwt.getClaim("realm_access");
        if (realm instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> values) {
            values.forEach(value -> roles.add(String.valueOf(value)));
        }
        var authorities = roles.stream()
                .map(value -> value.startsWith("ROLE_") ? value : "ROLE_" + value.toUpperCase(Locale.ROOT))
                .map(SimpleGrantedAuthority::new).toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
