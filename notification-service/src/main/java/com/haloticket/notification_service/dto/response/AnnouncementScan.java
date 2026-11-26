package com.haloticket.notification_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnnouncementScan(Status status, Integer score, Level level, List<String> findings) {
    public enum Status { COMPLETE, UNAVAILABLE }
    public enum Level { LOW, MEDIUM, HIGH }

    public static AnnouncementScan unavailable() {
        return new AnnouncementScan(Status.UNAVAILABLE, null, null, null);
    }
}
