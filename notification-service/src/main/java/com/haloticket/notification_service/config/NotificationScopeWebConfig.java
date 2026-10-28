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

/** Applies {@link NotificationScopeGuard} to /api/notifications without touching the controller. */
@Configuration
@RequiredArgsConstructor
public class NotificationScopeWebConfig implements WebMvcConfigurer {

    private static final String BASE = "/api/notifications";

    // looked up per request: the guard needs a Feign client, which needs the MVC configuration this class is part of
    private final ObjectProvider<NotificationScopeGuard> guards;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                String method = request.getMethod();
                String authorization = request.getHeader("Authorization");
                if (!path.startsWith(BASE)) return true;
                String rest = path.substring(BASE.length());
                NotificationScopeGuard guard = guards.getObject();
                if (rest.startsWith("/users/")) {
                    // GET /users/{userId} and PATCH /users/{userId}/read-all
                    String userId = rest.substring("/users/".length()).split("/")[0];
                    guard.requireOwnerOfUserInbox(userId, authorization);
                } else if ("PATCH".equals(method) && rest.endsWith("/read")) {
                    guard.requireOwnerOfNotification(rest.substring(1, rest.length() - "/read".length()), authorization);
                } else {
                    // create, publish, templates, sent/failed marks: other services and admins only
                    guard.requireInternalOrAdmin(request.getHeader("X-Internal-Token"));
                }
                return true;
            }
        }).addPathPatterns(BASE, BASE + "/**");
    }
}
