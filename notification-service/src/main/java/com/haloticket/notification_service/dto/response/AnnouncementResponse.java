package com.haloticket.notification_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.haloticket.notification_service.entity.AdminAnnouncement.Status;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnnouncementResponse(String id, Status status, long recipientCount, long deliveredCount,
                                   String errorCode) {}
