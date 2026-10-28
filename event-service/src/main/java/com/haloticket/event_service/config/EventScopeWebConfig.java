package com.haloticket.event_service.config;

import com.haloticket.event_service.authz.EventScopeProperties;
import com.haloticket.event_service.service.EventAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class EventScopeWebConfig implements WebMvcConfigurer {

    // looked up on first use: the access service needs the Feign client for user-service, and Feign needs the MVC configuration
    // this class belongs to, so a direct dependency would be a cycle
    private final ObjectProvider<EventAccessService> access;
    private final EventScopeProperties scope;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String method = request.getMethod();
                boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
                if (!read && scope.vouchersOn()) {
                    access.getObject().requirePlatformAdmin();
                }
                return true;
            }
        }).addPathPatterns("/api/events/admin/config/**", "/api/events/categories", "/api/events/categories/**",
                "/api/events/venues", "/api/events/venues/**");
    }
}
