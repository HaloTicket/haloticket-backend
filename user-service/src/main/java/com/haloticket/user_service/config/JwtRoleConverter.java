package com.haloticket.user_service.config;

import com.haloticket.user_service.enums.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Chuyển JWT của Keycloak thành xác thực Spring Security, ánh xạ claim {@code roles} sang vai trò của ứng dụng. */
@Component
public class JwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {

    /**
     * Dựng đối tượng xác thực từ JWT, quyền có dạng {@code ROLE_<vai trò>}.
     *
     * @param jwt token đã được xác thực
     * @return xác thực với subject của token làm tên
     */
    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = toApplicationRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * Ánh xạ claim {@code roles} sang vai trò ứng dụng: {@code ROLE_USER} thành {@code CUSTOMER}, {@code ROLE_ADMIN} thành {@code ADMIN}.
     * Không có vai trò nào khớp thì mặc định {@code CUSTOMER}.
     *
     * @param jwt token cần đọc
     * @return tập vai trò, không bao giờ rỗng
     */
    public Set<Role> toApplicationRoles(Jwt jwt) {
        List<String> keycloakRoles = jwt.getClaimAsStringList("roles");
        Set<Role> roles = new HashSet<>();

        if (keycloakRoles != null) {
            for (String keycloakRole : keycloakRoles) {
                switch (keycloakRole) {
                    case "ROLE_USER" -> roles.add(Role.CUSTOMER);
                    case "ROLE_ADMIN" -> roles.add(Role.ADMIN);
                    // ROLE_ORGANIZER và ROLE_STAFF bị bỏ qua có chủ ý: không nơi nào phân quyền theo hai vai trò này (organizer và staff
                    default -> {
                    }
                }
            }
        }

        if (roles.isEmpty()) {
            roles.add(Role.CUSTOMER);
        }

        return roles;
    }
}
