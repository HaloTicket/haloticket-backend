package com.haloticket.notification_service.dto.request;

import com.haloticket.notification_service.enums.AnnouncementCategory;

import java.util.UUID;

public final class OrganizerAnnouncementRequest {
    private OrganizerAnnouncementRequest() {}

    public record Submit(UUID requestId, String title, String message, AnnouncementCategory category,
                         AnnouncementTarget target, Long expectedRecipientCount) {
        public Submit(UUID requestId, String title, String message, AnnouncementCategory category) {
            this(requestId, title, message, category, null, null);
        }

        public Content content() {
            if (requestId == null) throw new IllegalArgumentException("A UUID requestId is required");
            return new Content(title, message, category);
        }
    }

    public record Resubmit(int revision, String title, String message, AnnouncementCategory category,
                           AnnouncementTarget target, Long expectedRecipientCount) {
        public Resubmit(int revision, String title, String message, AnnouncementCategory category) {
            this(revision, title, message, category, null, null);
        }

        public Content content() {
            requireRevision(revision);
            return new Content(title, message, category);
        }
    }

    public record Review(int revision, Action action, String reason) {
        public String validatedReason() {
            requireRevision(revision);
            if (action == null) throw new IllegalArgumentException("A moderation action is required");
            if (action == Action.APPROVE && (reason == null || reason.isBlank())) return null;
            return AnnouncementRequest.required(reason, 1000, "reason");
        }
    }

    public enum Action { APPROVE, REQUEST_CHANGES, REJECT }

    public record Preview(AnnouncementTarget target) {
        public Preview {
            if (target == null) throw new IllegalArgumentException("A target is required");
        }
    }

    public record RecipientCount(long recipientCount) {}

    public record Content(String title, String message, AnnouncementCategory category) {
        public Content {
            title = AnnouncementRequest.required(title, 150, "title");
            message = AnnouncementRequest.required(message, 5000, "message");
            if (category == null) throw new IllegalArgumentException("A category is required");
        }

        public void validateCategory(AnnouncementTarget target) {
            if ((category == AnnouncementCategory.INTERNAL && !target.staff())
                    || (category == AnnouncementCategory.PROMOTION && target.staff())) {
                throw new IllegalArgumentException("Category is unavailable for the target group");
            }
        }
    }

    private static void requireRevision(int revision) {
        if (revision < 1) throw new IllegalArgumentException("A positive revision is required");
    }
}
