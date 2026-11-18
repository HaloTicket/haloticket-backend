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
 * Áp {@link PaymentScopeGuard} cho ba endpoint có thể lộ dữ liệu của người khác mà không phải sửa controller.
 */
@Configuration
@RequiredArgsConstructor
public class PaymentScopeWebConfig implements WebMvcConfigurer {

    // tra cứu theo từng request: guard cần Feign client, mà Feign client cần cấu hình MVC chứa chính lớp này
    private final ObjectProvider<PaymentScopeGuard> guards;

    /**
     * Đăng ký interceptor gọi {@code PaymentScopeGuard} cho tài khoản nhận tiền, tra cứu verify và tra cứu hàng loạt phương thức.
     *
     * @param registry bộ đăng ký interceptor
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            /**
             * Kiểm tra quyền theo đường dẫn và phương thức HTTP trước khi vào controller; guard ném ngoại lệ khi bị từ chối.
             *
             * @param request yêu cầu HTTP
             * @param response phản hồi HTTP
             * @param handler handler đích
             * @return luôn true khi guard không từ chối
             */
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
