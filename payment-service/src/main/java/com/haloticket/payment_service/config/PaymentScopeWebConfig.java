package com.haloticket.payment_service.config;

import com.haloticket.payment_service.authz.PaymentScopeGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link PaymentScopeGuard} to the three endpoints that expose another person's data, without touching the
 * controller. Each check returns at once while the identity switch is off.
 */
@Configuration
@RequiredArgsConstructor
public class PaymentScopeWebConfig implements WebMvcConfigurer {

    // looked up per request: the guard needs Feign clients, which need the MVC configuration that this class is part of
    private final ObjectProvider<PaymentScopeGuard> guards;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                String method = request.getMethod();
                String authorization = request.getHeader("Authorization");
                if (HttpMethod.GET.matches(method) && path.startsWith("/api/payments/organizer-payout-account/")) {
                    guards.getObject().requirePayoutAccountOwner(path.substring("/api/payments/organizer-payout-account/".length()), authorization);
                } else if (HttpMethod.GET.matches(method) && path.startsWith("/api/payments/verify/")) {
                    guards.getObject().requireBookingBuyer(path.substring("/api/payments/verify/".length()), authorization);
                } else if (HttpMethod.POST.matches(method) && path.equals("/api/payments/methods/bulk")) {
                    guards.getObject().requireInternalOrAdmin(request.getHeader("X-Internal-Token"));
                }
                return true;
            }
        }).addPathPatterns("/api/payments/organizer-payout-account/*", "/api/payments/verify/*", "/api/payments/methods/bulk");
    }
}
