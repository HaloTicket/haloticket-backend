package com.haloticket.event_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JwtRoleConverter implements Converter<Jwt, JwtAuthenticationToken> {
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
