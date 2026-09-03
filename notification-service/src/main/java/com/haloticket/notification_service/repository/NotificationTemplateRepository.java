package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
    List<NotificationTemplate> findByStatus(NotificationTemplateStatus status);

    Optional<NotificationTemplate> findByTypeAndChannelAndStatus(
            NotificationType type,
            NotificationChannel channel,
            NotificationTemplateStatus status
    );
}
