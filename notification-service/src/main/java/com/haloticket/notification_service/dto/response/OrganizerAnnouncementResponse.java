package com.haloticket.notification_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Kind;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import com.haloticket.notification_service.entity.AdminAnnouncement.Status;
import com.haloticket.notification_service.enums.AnnouncementCategory;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrganizerAnnouncementResponse(String id, String eventId, String eventTitle, String title, String message,
                                            AnnouncementCategory category, Kind audience, Status status, int revision,
                                            Instant submittedAt, Instant reviewedAt, String reviewReason,
                                            long recipientCount, long deliveredCount, AnnouncementScan scan,
                                            AnnouncementTarget target, Long previewRecipientCount, String errorCode) {
    public record Page(List<OrganizerAnnouncementResponse> items, long total) {}
}
