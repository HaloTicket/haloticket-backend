package com.haloticket.notification_service.dto.request;

import com.haloticket.notification_service.enums.NotificationPriority;

import java.util.UUID;
import java.util.regex.Pattern;

public record AnnouncementRequest(String requestId, String title, String message, NotificationPriority priority,
                                  Audience audience) {
    private static final Pattern UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");

    public AnnouncementRequest validated() {
        if (requestId == null || !UUID_PATTERN.matcher(requestId).matches()
                || priority == null || audience == null || audience.kind() == null) {
            throw new IllegalArgumentException("A UUID requestId, priority and audience are required");
        }

        Audience normalized = switch (audience.kind()) {
            case ALL -> {
                if (audience.userId() != null || audience.eventId() != null) throw invalidAudience();
                yield audience;
            }
            case USER -> {
                if (audience.eventId() != null || !hasIdentifier(audience.userId())) throw invalidAudience();
                yield new Audience(audience.kind(), audience.userId().strip(), null);
            }
            case EVENT_ORGANIZING_TEAM -> {
                if (audience.userId() != null || !hasIdentifier(audience.eventId())) throw invalidAudience();
                yield new Audience(audience.kind(), null, audience.eventId().strip());
            }
            case EVENT_BUYERS, EVENT_STAFF -> throw invalidAudience();
        };

        return new AnnouncementRequest(UUID.fromString(requestId).toString(), required(title, 150, "title"),
                required(message, 5000, "message"), priority, normalized);
    }

    private static boolean hasIdentifier(String value) {
        return value != null && !value.isBlank() && value.strip().length() <= 128;
    }

    static String required(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " must contain between 1 and " + max + " characters");
        }
        return normalized;
    }

    private static IllegalArgumentException invalidAudience() {
        return new IllegalArgumentException("Invalid recipient fields for the selected audience");
    }

    public record Audience(Kind kind, String userId, String eventId) {}

    public enum Kind {
        ALL, USER, EVENT_ORGANIZING_TEAM, EVENT_BUYERS, EVENT_STAFF
    }
}
