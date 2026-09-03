package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, String> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status);
}
