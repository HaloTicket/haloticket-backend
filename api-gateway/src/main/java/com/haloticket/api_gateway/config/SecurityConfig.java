package com.haloticket.api_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Cấu hình bảo mật WebFlux của gateway: xác thực JWT, CORS và phân quyền theo từng nhóm đường dẫn API.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${KEYCLOAK_ALLOWED_ISSUER_URIS:}")
    private String allowedIssuerUris;

    /** Bật để chỉ nhận token có audience {@code api-gateway}. Mặc định tắt để không khóa người dùng khi Keycloak chưa gắn audience mapper. */
    @Value("${gateway.jwt.require-audience:false}")
    private boolean requireAudience;

    /**
     * Tạo bộ giải mã JWT: dùng {@code jwk-set-uri} nếu được cấu hình, nếu không thì tra cứu theo issuer. Token phải đúng issuer; khi {@code gateway.jwt.require-audience=true} còn phải có audience {@code api-gateway}.
     *
     * @return bộ giải mã JWT dạng reactive
     */
    @Bean("reactiveJwtDecoder")
    ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = org.springframework.util.StringUtils.hasText(jwkSetUri)
        NimbusReactiveJwtDecoder decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        var issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        var issuerValidator = issuerValidator();
        decoder.setJwtValidator(requireAudience
                ? new DelegatingOAuth2TokenValidator<>(issuerValidator, new AudienceValidator("api-gateway"))
                : issuerValidator);
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> issuerValidator() {
        Set<String> allowedIssuers = new LinkedHashSet<>();
        if (StringUtils.hasText(issuerUri)) {
            allowedIssuers.add(issuerUri.trim());
        }
        if (StringUtils.hasText(allowedIssuerUris)) {
            Arrays.stream(allowedIssuerUris.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(allowedIssuers::add);
        }
        if (allowedIssuers.size() <= 1) {
            return JwtValidators.createDefaultWithIssuer(issuerUri);
        }
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS, iss -> iss != null && allowedIssuers.contains(iss.toString())));
    }

    @Value("${management.server.port:0}")
    private int managementPort;

    @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000,http://localhost:5174,https://haloticket-web.pages.dev}")
    private String allowedOrigins;

    /**
     * Nguồn cấu hình CORS cho Spring Security, cùng nội dung với {@code CorsConfig}: origin cấu hình cộng thêm các domain pages.dev, cho phép credentials.
     *
     * @return cấu hình CORS áp dụng cho mọi đường dẫn
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        java.util.List<String> origins = new java.util.ArrayList<>(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        if (!origins.contains("https://haloticket-web.pages.dev")) {
            origins.add("https://haloticket-web.pages.dev");
        }
        if (!origins.contains("https://*.pages.dev")) {
            origins.add("https://*.pages.dev");
        }

        corsConfig.setAllowedOriginPatterns(origins);
        corsConfig.setMaxAge(3600L);
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        corsConfig.setAllowedHeaders(Collections.singletonList("*"));
        // cho phép web đọc header Retry-After để biết phải chờ bao lâu khi nhận HTTP 429
        corsConfig.setExposedHeaders(Arrays.asList("Retry-After"));
        corsConfig.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        return source;
    }

    /**
     * Dựng chuỗi phân quyền theo thứ tự quy tắc: chặn đường dẫn nội bộ, cho phép webhook thanh toán và các lượt đọc công khai,
     * giới hạn khu vực admin cho ROLE_ADMIN, các đường dẫn còn lại yêu cầu đăng nhập. Tắt CSRF vì dùng token Bearer.
     *
     * @param http cấu hình bảo mật HTTP
     * @return chuỗi bộ lọc bảo mật
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> {
                    exchange
                            // actuator trên cổng management (không publish) dành cho các container khác; trên cổng công khai
                            // quy tắc kế tiếp vẫn từ chối /actuator/**
                            .matchers(request -> InternalPathBlockFilter.arrivedOnManagementPort(request.getRequest().getLocalAddress(), managementPort)
                                    && InternalPathBlockFilter.isActuatorPath(request.getRequest().getURI().getRawPath())
                                    ? org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.match()
                                    : org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch())
                            .permitAll()
                            // lớp chặn thứ hai, phía sau InternalPathBlockFilter
                            .pathMatchers("/api/internal/**", "/*/api/internal/**", "/actuator/**", "/eureka/**").denyAll()
                            .pathMatchers(HttpMethod.OPTIONS).permitAll()
                            .pathMatchers("/ws/**", "/api/payments/vnpay/ipn", "/api/payments/momo/ipn", "/api/payments/zalopay/ipn", "/api/payments/sepay/webhook").permitAll()
                            .pathMatchers("/api/events/admin/reports", "/api/events/admin/reports/**",
                                    "/api/payments/admin/report-payments")
                            .access(SecurityConfig::adminOnly);
                    // Các route /api/organizer/** chỉ yêu cầu đăng nhập ở gateway.
                    // AI assistant kiểm tra quyền của người gọi theo sự kiện.
                    exchange.pathMatchers("/api/admin/**", "/api/payouts/admin/**", "/api/users/admin", "/api/users/admin/**",
                            // đặt trước quy tắc chung /api/bookings/** ở dưới (chỉ yêu cầu đăng nhập)
                            "/api/bookings/admin/**",
                            // bộ đếm admin chỉ đọc; marketplace không có ánh xạ vai trò nên quy tắc ở gateway là lớp khóa đầu tiên
                            "/api/marketplace/admin/**")
                            .access(SecurityConfig::adminOnly);
                    // mở cho khách ẩn danh: đọc sự kiện, loại vé và sơ đồ ghế, bộ đếm lượt xem và tìm kiếm,
                    // cấu hình trang chủ. Mọi thao tác ghi khác đều cần đăng nhập.
                    // sửa cấu hình trang chủ, danh mục và địa điểm chỉ dành cho admin nền tảng (event-service cũng kiểm tra);
                    // các lượt đọc bên dưới vẫn mở vì trang chủ cần chúng
                    for (HttpMethod write : Arrays.asList(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                        exchange.pathMatchers(write, "/api/events/admin/config/**", "/api/events/categories", "/api/events/categories/**",
                                "/api/events/venues", "/api/events/venues/**").access(SecurityConfig::adminOnly);
                    }
                    exchange.pathMatchers(HttpMethod.POST, "/api/events/*/view", "/api/events/search-log").permitAll()
                            .pathMatchers(HttpMethod.GET, "/api/events/*/analytics", "/api/events/my-events", "/api/events/organizer-profile",
                                    "/api/events/*/members", "/api/events/*/members/**", "/api/events/*/transfer-settings").authenticated()
                            .pathMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                            .pathMatchers("/api/events/**").authenticated()
                            .pathMatchers("/api/tickets/checkin", "/api/tickets/checkin/**").authenticated()
                            .pathMatchers(HttpMethod.GET, "/api/tickets/**").permitAll()
                            .pathMatchers("/api/tickets/**").authenticated()
                            .pathMatchers(HttpMethod.GET, "/api/media/**").permitAll()
                            // xóa tệp theo khóa tùy ý: media-service không kiểm tra chủ tệp nên chỉ admin được gọi (web và mobile không dùng)
                            .pathMatchers(HttpMethod.DELETE, "/api/media/**").access(SecurityConfig::adminOnly)
                            .pathMatchers("/api/media/**").authenticated()
                            .pathMatchers("/api/v1/**", "/ws-notifications/**").permitAll();
                    exchange
                            .pathMatchers("/api/marketplace/listings/me", "/api/marketplace/sagas/**").authenticated()
                            .pathMatchers(HttpMethod.GET,
                                    "/api/marketplace/events", "/api/marketplace/events/**",
                                    "/api/marketplace/listings", "/api/marketplace/listings/*").permitAll()
                            .pathMatchers("/api/marketplace/**").authenticated()
                            .pathMatchers("/api/users/me").authenticated()
                            .pathMatchers("/api/users/**", "/api/bookings/**", "/api/payments/**", "/api/notifications/**")
                            .authenticated();
                    exchange
                            .pathMatchers("/api/seats/**", "/api/gifts/**", "/api/payouts/**", "/api/organizer/**").authenticated()
                            // khách ẩn danh dùng widget chatbot, đăng nhập, đăng ký và đặt lại mật khẩu.
                            .pathMatchers("/api/customer/**", "/api/auth/**").permitAll()
                            .anyExchange().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder())));

        return http.build();
    }

    /**
     * Quy tắc truy cập chỉ cho quản trị viên; không có xác thực thì từ chối.
     *
     * @param authentication thông tin xác thực của người gọi
     * @param context ngữ cảnh phân quyền
     * @return quyết định cho phép hoặc từ chối
     */
    static Mono<AuthorizationDecision> adminOnly(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
                .map(auth -> new AuthorizationDecision(isReportAdmin(auth)))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /**
     * Kiểm tra người gọi đã đăng nhập bằng JWT và có vai trò ADMIN trong claim {@code roles} hoặc {@code realm_access.roles}.
     *
     * @param authentication thông tin xác thực
     * @return {@code true} nếu là quản trị viên
     */
    static boolean isReportAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) return false;
        Object direct = token.getToken().getClaim("roles");
        Object realm = token.getToken().getClaim("realm_access");
        return hasAdminRole(direct) || (realm instanceof java.util.Map<?, ?> map && hasAdminRole(map.get("roles")));
    }

    /**
     * Kiểm tra tập vai trò có chứa {@code ADMIN} hoặc {@code ROLE_ADMIN} (không phân biệt hoa thường).
     *
     * @param roles giá trị claim vai trò, có thể không phải tập hợp
     * @return {@code true} nếu có vai trò admin
     */
    private static boolean hasAdminRole(Object roles) {
        return roles instanceof java.util.Collection<?> values && values.stream()
                .anyMatch(value -> "ADMIN".equalsIgnoreCase(String.valueOf(value))
                        || "ROLE_ADMIN".equalsIgnoreCase(String.valueOf(value)));
    }
}
