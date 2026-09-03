package com.haloticket.notification_service.service;

import com.haloticket.notification_service.dto.request.NotificationCreateRequest;
import com.haloticket.notification_service.dto.request.NotificationTemplateCreateRequest;
import com.haloticket.notification_service.dto.response.NotificationResponse;
import com.haloticket.notification_service.dto.response.NotificationTemplateResponse;

import java.util.List;

public interface NotificationService {
    NotificationTemplateResponse createTemplate(NotificationTemplateCreateRequest request);

    List<NotificationTemplateResponse> getTemplates();

    NotificationResponse createNotification(NotificationCreateRequest request);

    List<NotificationResponse> getUserNotifications(String userId);

    NotificationResponse markAsSent(String id);

    NotificationResponse markAsFailed(String id);
}
