package com.haloticket.notification_service.config;

import com.haloticket.notification_service.authz.NotificationScopeGuard;
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
public class NotificationScopeWebConfig implements WebMvcConfigurer {
    private static final String BASE = "/api/notifications";

    private final ObjectProvider<NotificationScopeGuard> guards;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                guards.getObject().requireInternalOrAdmin(request.getHeader("X-Internal-Token"));
                return true;
            }
        }).addPathPatterns(BASE, BASE + "/publish", BASE + "/templates", BASE + "/*/sent", BASE + "/*/failed");
    }
}
