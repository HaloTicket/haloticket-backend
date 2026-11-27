package com.haloticket.notification_service.service;

import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Kind;
import com.haloticket.notification_service.repository.AnnouncementStore;
import com.haloticket.notification_service.service.impl.NotificationServiceImpl;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.announcements.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AdminAnnouncementWorker {
    private static final int PAGE_SIZE = 200;
    private static final int DELIVERY_BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final AnnouncementStore store;
    private final RecipientDirectory directory;
    private final NotificationServiceImpl notifications;
    private final OrganizerRecipients organizerRecipients;

    @Scheduled(fixedDelayString = "${notification.announcements.poll-interval-ms:1000}")
    public void runNext() {
        var job = store.claim();
        if (job == null) return;
        Kind kind = job.getPayload().audience().kind();
        boolean organizer = job.getOrganizerEventId() != null;
        if ((kind == Kind.EVENT_BUYERS || kind == Kind.EVENT_STAFF) != organizer
                || (organizer && (job.getEventTitle() == null || job.getCategory() == null
                        || (kind == Kind.EVENT_STAFF) != job.effectiveTarget().staff()))) {
            store.fail(job, "INVALID_ORGANIZER_ANNOUNCEMENT");
            return;
        }
        if (job.getAttempts() > MAX_ATTEMPTS) {
            store.fail(job, "DELIVERY_RETRY_EXHAUSTED");
            return;
        }

        try {
            if (job.getSnapshotId() == null) {
                freezeRecipients(job);
            }
            deliver(job);
        } catch (LeaseExpired ignored) {
            log.debug("Announcement lease expired for {}", job.getId());
        } catch (IneligibleAudience exception) {
            store.fail(job, exception.code);
        } catch (FeignException.NotFound exception) {
            store.fail(job, "RECIPIENT_NOT_FOUND");
        } catch (FeignException.Forbidden exception) {
            store.fail(job, "RECIPIENT_SCOPE_FORBIDDEN");
        } catch (RuntimeException exception) {
            log.warn("Announcement {} failed on attempt {}", job.getId(), job.getAttempts(), exception);
            if (job.getAttempts() < MAX_ATTEMPTS) {
                store.retry(job);
            } else {
                store.fail(job, "DELIVERY_RETRY_EXHAUSTED");
            }
        }
    }

    private void freezeRecipients(AdminAnnouncement job) {
        if (job.getOrganizerEventId() != null) {
            organizerRecipients.forEachBatch(job.getOrganizerEventId(), job.effectiveDeliveryActorId(),
                    job.effectiveTarget(), () -> requireLease(store.renew(job)), ids -> {
                        requireLease(store.renew(job));
                        store.addRecipients(job, ids);
                    });
            long count = store.countStagedRecipients(job);
            if (job.isRecipientCountConfirmed() && !Long.valueOf(count).equals(job.getPreviewRecipientCount())) {
                throw new IneligibleAudience("RECIPIENT_COUNT_CHANGED");
            }
            if (count == 0) throw new IneligibleAudience("NO_ELIGIBLE_RECIPIENTS");
            sealSnapshot(job, count);
            return;
        }
        var audience = job.getPayload().audience();
        Instant cutoff = job.getCreatedAt();
        String cursor = "";
        while (true) {
            requireLease(store.renew(job));
            List<String> page = switch (audience.kind()) {
                case ALL -> directory.activeAccounts(cutoff, cursor, PAGE_SIZE);
                case USER -> List.of(directory.activeAccount(audience.userId(), cutoff));
                case EVENT_ORGANIZING_TEAM -> directory.eventTeam(audience.eventId(), cutoff, cursor, PAGE_SIZE);
                case EVENT_BUYERS, EVENT_STAFF -> throw new IllegalStateException("An organizer event is required");
            };

            List<String> eligible = switch (audience.kind()) {
                case ALL, USER -> page;
                case EVENT_ORGANIZING_TEAM -> directory.activeMatches(cutoff, page);
                case EVENT_BUYERS, EVENT_STAFF -> throw new IllegalStateException("An organizer event is required");
            };
            requireLease(store.renew(job));
            store.addRecipients(job, eligible);
            if (page.size() < PAGE_SIZE) break;
            String next = page.getLast();
            if (cursor.equals(next)) throw new IllegalStateException("Recipient cursor did not advance");
            cursor = next;
        }

        long count = store.countStagedRecipients(job);
        if (count == 0 && (audience.kind() == Kind.USER || audience.kind() == Kind.EVENT_ORGANIZING_TEAM)) {
            throw new IneligibleAudience("NO_ELIGIBLE_RECIPIENTS");
        }
        sealSnapshot(job, count);
    }

    private void sealSnapshot(AdminAnnouncement job, long count) {
        // The token identifies a complete snapshot only after this compare-and-set succeeds.
        requireLease(store.sealSnapshot(job, count));
        job.setSnapshotId(job.getLeaseToken());
        store.discardOldSnapshots(job);
    }

    private void deliver(AdminAnnouncement job) {
        var payload = job.getPayload();
        while (true) {
            requireLease(store.renew(job));
            var batch = store.pendingRecipients(job, job.getSnapshotId(), DELIVERY_BATCH_SIZE);
            if (batch.isEmpty()) {
                store.complete(job);
                return;
            }
            for (var recipient : batch) {
                if (job.getOrganizerEventId() != null) {
                    notifications.deliverOrganizerAnnouncement(job, recipient.userId());
                } else {
                    notifications.deliverAnnouncement(job.getId(), recipient.userId(), payload.title(), payload.message(),
                            payload.priority());
                }
                store.markDelivered(recipient);
            }
        }
    }

    private static void requireLease(boolean valid) {
        if (!valid) throw new LeaseExpired();
    }

    private static class LeaseExpired extends RuntimeException {}

    private static class IneligibleAudience extends RuntimeException {
        private final String code;

        private IneligibleAudience(String code) {
            this.code = code;
        }
    }
}
