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

/** Áp dụng {@code NotificationScopeGuard} cho {@code /api/notifications} mà không phải sửa controller. */
@Configuration
@RequiredArgsConstructor
public class NotificationScopeWebConfig implements WebMvcConfigurer {

    private static final String BASE = "/api/notifications";

    // tra cứu theo từng yêu cầu: guard cần Feign client, mà Feign client lại cần cấu hình MVC chứa chính lớp này
    private final ObjectProvider<NotificationScopeGuard> guards;

    /**
     * Đăng ký interceptor kiểm tra quyền cho các đường dẫn bắt đầu bằng {@code /api/notifications}.
     *
     * @param registry nơi đăng ký interceptor
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            /**
             * Phân loại yêu cầu để kiểm tra quyền: {@code /users/{userId}} cần là chủ hộp thư, {@code PATCH .../read} cần là chủ thông báo,
             * các yêu cầu còn lại chỉ dành cho dịch vụ nội bộ (token {@code X-Internal-Token}) hoặc admin. Ném lỗi từ guard khi không đủ quyền.
             *
             * @param request yêu cầu HTTP
             * @param response phản hồi HTTP
             * @param handler handler sẽ xử lý yêu cầu
             * @return {@code true} để tiếp tục xử lý khi qua được kiểm tra
             */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getRequestURI();
                String method = request.getMethod();
                String authorization = request.getHeader("Authorization");
                if (!path.startsWith(BASE)) return true;
                String rest = path.substring(BASE.length());
                NotificationScopeGuard guard = guards.getObject();
                if (rest.startsWith("/users/")) {
                    // GET /users/{userId} và PATCH /users/{userId}/read-all
                    String userId = rest.substring("/users/".length()).split("/")[0];
                    guard.requireOwnerOfUserInbox(userId, authorization);
                } else if ("PATCH".equals(method) && rest.endsWith("/read")) {
                    guard.requireOwnerOfNotification(rest.substring(1, rest.length() - "/read".length()), authorization);
                } else {
                    // tạo, phát hành, mẫu, đánh dấu đã gửi/thất bại: chỉ dành cho dịch vụ khác và admin
                    guard.requireInternalOrAdmin(request.getHeader("X-Internal-Token"));
                }
                return true;
            }
        }).addPathPatterns(BASE, BASE + "/**");
    }
}
