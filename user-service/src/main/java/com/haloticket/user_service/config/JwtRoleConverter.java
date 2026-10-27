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

@Component
public class JwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = toApplicationRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    public Set<Role> toApplicationRoles(Jwt jwt) {
        List<String> keycloakRoles = jwt.getClaimAsStringList("roles");
        Set<Role> roles = new HashSet<>();

        if (keycloakRoles != null) {
            for (String keycloakRole : keycloakRoles) {
                switch (keycloakRole) {
                    case "ROLE_USER" -> roles.add(Role.CUSTOMER);
                    case "ROLE_ADMIN" -> roles.add(Role.ADMIN);
                    // ROLE_ORGANIZER and ROLE_STAFF are ignored on purpose: nothing authorizes by them (organizer and staff
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
