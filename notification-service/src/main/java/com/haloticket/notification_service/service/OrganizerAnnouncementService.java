package com.haloticket.notification_service.service;

import com.haloticket.notification_service.client.AnnouncementScanClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient.EventScope;
import com.haloticket.notification_service.dto.request.AnnouncementRequest;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Audience;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Kind;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import com.haloticket.notification_service.dto.request.OrganizerAnnouncementRequest.*;
import com.haloticket.notification_service.dto.response.OrganizerAnnouncementResponse;
import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.entity.AdminAnnouncement.ReviewEntry;
import com.haloticket.notification_service.entity.AdminAnnouncement.Status;
import com.haloticket.notification_service.enums.NotificationPriority;
import com.haloticket.notification_service.repository.AnnouncementStore;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizerAnnouncementService {
    private final AnnouncementStore store;
    private final EventAnnouncementScopeClient events;
    private final AnnouncementScanClient scanner;
    private final OrganizerRecipients recipients;

    public OrganizerAnnouncementResponse.Page list(String eventId, String authorization, int page, int size) {
        return page(requireScope(eventId, authorization).eventId(), "", page, size);
    }

    public OrganizerAnnouncementResponse.Page pending(String q, int page, int size) {
        String search = q == null ? "" : q.strip();
        if (search.length() > 150) throw new IllegalArgumentException("Search exceeds 150 characters");
        return page(null, search, page, size);
    }

    public RecipientCount recipientCount(String eventId, String authorization, AnnouncementTarget target) {
        return new RecipientCount(count(requireScope(eventId, authorization), target));
    }

    public OrganizerAnnouncementResponse submit(String eventId, String authorization, Submit request) {
        var scope = requireScope(eventId, authorization);
        var content = request.content();
        var target = request.target() == null ? AnnouncementTarget.CUSTOMER_ALL : request.target();
        content.validateCategory(target);
        validateExpectation(request.expectedRecipientCount(), request.target() != null);
        String requestId = request.requestId().toString();
        String fingerprint = fingerprint(content, target);
        var previous = store.findByActorAndRequest(scope.userId(), requestId);
        if (previous != null) return existing(previous, eventId, fingerprint);

        long count = confirmedCount(scope, target, request.expectedRecipientCount());
        var scan = target.staff() ? null : scanner.scan(content.title(), content.message());
        Status initialStatus = target.staff() ? Status.QUEUED : Status.PENDING_REVIEW;
        Instant now = Instant.now();
        var job = AdminAnnouncement.builder().id(UUID.randomUUID().toString())
                .actorId(scope.userId()).requestId(requestId)
                .organizerEventId(scope.eventId()).eventTitle(scope.eventTitle()).category(content.category())
                .payload(payload(requestId, scope.eventId(), content, target)).initialContentHash(fingerprint)
                .target(target).previewRecipientCount(count).recipientCountConfirmed(request.expectedRecipientCount() != null)
                .status(initialStatus).revision(1).createdAt(now).submittedAt(now).scan(scan)
                .availableAt(target.staff() ? now : null)
                .history(List.of(new ReviewEntry(1, initialStatus, scope.userId(), now, null))).build();
        try {
            return response(store.insert(job));
        } catch (DuplicateKeyException collision) {
            var winner = store.findByActorAndRequest(scope.userId(), requestId);
            if (winner == null) throw collision;
            return existing(winner, eventId, fingerprint);
        }
    }

    public OrganizerAnnouncementResponse resubmit(String eventId, String id, String authorization, Resubmit request) {
        var scope = requireScope(eventId, authorization);
        var content = request.content();
        var job = requireJob(id, scope.eventId());
        if (job.getRevision() != request.revision() || job.getStatus() != Status.CHANGES_REQUESTED) throw conflict();

        var target = request.target() == null ? job.effectiveTarget() : request.target();
        content.validateCategory(target);
        validateExpectation(request.expectedRecipientCount(), request.target() != null || job.isRecipientCountConfirmed());
        long count = confirmedCount(scope, target, request.expectedRecipientCount());
        var scan = target.staff() ? null : scanner.scan(content.title(), content.message());
        Status next = target.staff() ? Status.QUEUED : Status.PENDING_REVIEW;
        Instant now = Instant.now();
        var update = Update.update("status", next).set("submittedAt", now)
                .set("payload", payload(job.getRequestId(), scope.eventId(), content, target))
                .set("target", target).set("previewRecipientCount", count)
                .set("recipientCountConfirmed", request.expectedRecipientCount() != null)
                .set("deliveryActorId", scope.userId())
                .set("category", content.category()).set("eventTitle", scope.eventTitle()).set("scan", scan)
                .unset("reviewedAt").unset("reviewReason");
        if (target.staff()) update.set("availableAt", now);
        var history = new ReviewEntry(request.revision() + 1, next, scope.userId(), now, null);
        return transition(id, scope.eventId(), request.revision(), Status.CHANGES_REQUESTED, update, history);
    }

    public OrganizerAnnouncementResponse review(String id, String actorId, Review request) {
        String reason = request.validatedReason();
        if (requireJob(id, null).effectiveTarget().staff()) {
            throw status(HttpStatus.NOT_FOUND, "ANNOUNCEMENT_NOT_FOUND");
        }
        Status next = switch (request.action()) {
            case APPROVE -> Status.QUEUED;
            case REQUEST_CHANGES -> Status.CHANGES_REQUESTED;
            case REJECT -> Status.REJECTED;
        };
        Instant now = Instant.now();
        var update = Update.update("status", next).set("reviewedAt", now).set("reviewReason", reason);
        if (next == Status.QUEUED) update.set("availableAt", now);
        var history = new ReviewEntry(request.revision() + 1, next, actorId, now, reason);
        return transition(id, null, request.revision(), Status.PENDING_REVIEW, update, history);
    }

    private OrganizerAnnouncementResponse.Page page(String eventId, String search, int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid page or size");
        }
        var jobs = store.organizerPage(eventId, search, page, size);
        return new OrganizerAnnouncementResponse.Page(jobs.items().stream().map(this::response).toList(), jobs.total());
    }

    private OrganizerAnnouncementResponse transition(String id, String eventId, int revision, Status expected,
                                                       Update update, ReviewEntry history) {
        var updated = store.transition(id, eventId, revision, expected, update, history);
        if (updated == null) throw conflict();
        return response(updated);
    }

    private EventScope requireScope(String eventId, String authorization) {
        if (authorization == null || authorization.isBlank()) throw status(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        if (!eventId.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid event ID");
        try {
            var scope = events.scope(eventId, authorization);
            if (scope != null && eventId.equals(scope.eventId()) && scope.userId() != null && !scope.userId().isBlank()
                    && scope.eventTitle() != null && ("OWNER".equals(scope.role()) || "MANAGER".equals(scope.role()))) {
                return scope;
            }
        } catch (FeignException exception) {
            switch (exception.status()) {
                case 401 -> throw status(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
                case 403 -> throw status(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
                case 404 -> throw status(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
                default -> throw status(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_SCOPE_UNAVAILABLE");
            }
        }
        throw status(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_SCOPE_UNAVAILABLE");
    }

    private AdminAnnouncement requireJob(String id, String eventId) {
        var job = store.findById(id);
        if (job == null || job.getOrganizerEventId() == null
                || (eventId != null && !eventId.equals(job.getOrganizerEventId()))) {
            throw status(HttpStatus.NOT_FOUND, "ANNOUNCEMENT_NOT_FOUND");
        }
        return job;
    }

    private OrganizerAnnouncementResponse existing(AdminAnnouncement job, String eventId, String fingerprint) {
        if (!eventId.equals(job.getOrganizerEventId()) || !fingerprint.equals(job.getInitialContentHash())) {
            throw status(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
        }
        return response(job);
    }

    private OrganizerAnnouncementResponse response(AdminAnnouncement job) {
        long delivered = job.getSnapshotId() == null ? 0 : store.countStoredNotifications(job.getId());
        return new OrganizerAnnouncementResponse(job.getId(), job.getOrganizerEventId(), job.getEventTitle(),
                job.getPayload().title(), job.getPayload().message(), job.getCategory(),
                job.effectiveTarget().staff() ? Kind.EVENT_ORGANIZING_TEAM : Kind.EVENT_BUYERS,
                job.getStatus(), job.getRevision(), job.getSubmittedAt(), job.getReviewedAt(), job.getReviewReason(),
                job.getRecipientCount(), delivered, job.getScan(), job.effectiveTarget(),
                job.getPreviewRecipientCount(), job.getErrorCode());
    }

    private static AnnouncementRequest payload(String requestId, String eventId, Content content, AnnouncementTarget target) {
        Kind audience = target.staff() ? Kind.EVENT_STAFF : Kind.EVENT_BUYERS;
        return new AnnouncementRequest(requestId, content.title(), content.message(), NotificationPriority.NORMAL,
                new Audience(audience, null, eventId));
    }

    private static String fingerprint(Content content, AnnouncementTarget target) {
        String serialized = content.category().name() + ":" + content.title().length() + ":" + content.title()
                + content.message();
        if (!AnnouncementTarget.CUSTOMER_ALL.equals(target)) {
            // Keep the original digest for legacy CUSTOMER ALL idempotency keys.
            serialized = target + "\n" + serialized;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long count(EventScope scope, AnnouncementTarget target) {
        try {
            return recipients.count(scope.eventId(), scope.userId(), target);
        } catch (FeignException exception) {
            throw switch (exception.status()) {
                case 403 -> status(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
                case 404 -> status(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND");
                default -> status(HttpStatus.SERVICE_UNAVAILABLE, "RECIPIENT_DIRECTORY_UNAVAILABLE");
            };
        }
    }

    private long confirmedCount(EventScope scope, AnnouncementTarget target, Long expected) {
        long count = count(scope, target);
        if (expected != null && expected != count) {
            throw status(HttpStatus.CONFLICT, "RECIPIENT_COUNT_CHANGED");
        }
        if (count == 0) throw status(HttpStatus.BAD_REQUEST, "NO_ELIGIBLE_RECIPIENTS");
        return count;
    }

    private static void validateExpectation(Long count, boolean required) {
        if ((count == null && required) || (count != null && count < 0)) {
            throw new IllegalArgumentException("A non-negative expectedRecipientCount is required for a target");
        }
    }

    private static ResponseStatusException conflict() {
        return status(HttpStatus.CONFLICT, "ANNOUNCEMENT_REVISION_OR_STATUS_CONFLICT");
    }

    private static ResponseStatusException status(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
