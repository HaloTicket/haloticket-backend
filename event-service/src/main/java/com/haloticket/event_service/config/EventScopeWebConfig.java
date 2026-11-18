package com.haloticket.event_service.config;

import com.haloticket.event_service.service.EventAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Cấu hình MVC gắn interceptor kiểm tra quyền quản trị nền tảng cho các thao tác ghi vào cấu hình trang chủ, danh mục và địa điểm. */
@Configuration
@RequiredArgsConstructor
public class EventScopeWebConfig implements WebMvcConfigurer {

    // tra cứu ở lần dùng đầu tiên: dịch vụ phân quyền cần Feign client của user-service, mà Feign lại cần cấu hình MVC
    // của chính lớp này, nên phụ thuộc trực tiếp sẽ tạo vòng lặp
    private final ObjectProvider<EventAccessService> access;

    /**
     * Đăng ký interceptor: với các đường dẫn quản trị, mọi yêu cầu không phải GET, HEAD hay OPTIONS đều đòi quyền quản trị nền tảng.
     * @param registry nơi đăng ký interceptor
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String method = request.getMethod();
                boolean read = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
                if (!read) {
                    access.getObject().requirePlatformAdmin();
                }
                return true;
            }
        }).addPathPatterns("/api/events/admin/config/**", "/api/events/categories", "/api/events/categories/**",
                "/api/events/venues", "/api/events/venues/**");
    }
}
