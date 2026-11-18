package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Repository JPA truy vấn mẫu thông báo ({@code NotificationTemplate}). */
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
    /**
     * Lấy các mẫu theo trạng thái.
     *
     * @param status trạng thái mẫu
     * @return danh sách mẫu
     */
    List<NotificationTemplate> findByStatus(NotificationTemplateStatus status);

    /**
     * Tìm mẫu theo loại, kênh và trạng thái.
     *
     * @param type loại thông báo
     * @param channel kênh gửi
     * @param status trạng thái mẫu
     * @return mẫu khớp nếu có
     */
    Optional<NotificationTemplate> findByTypeAndChannelAndStatus(
            NotificationType type,
            NotificationChannel channel,
            NotificationTemplateStatus status
    );
}
