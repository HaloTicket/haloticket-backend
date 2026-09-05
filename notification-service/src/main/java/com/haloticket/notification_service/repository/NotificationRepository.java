package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, String> {
    Optional<Notification> findByEventId(String eventId);
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Notification> findByStatusOrderByCreatedAtAsc(NotificationStatus status);
}
