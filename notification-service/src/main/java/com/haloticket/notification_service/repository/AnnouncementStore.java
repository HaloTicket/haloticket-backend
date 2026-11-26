package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.entity.AnnouncementRecipient;
import com.haloticket.notification_service.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.haloticket.notification_service.entity.AdminAnnouncement.Status.*;

@Repository
@RequiredArgsConstructor
public class AnnouncementStore {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final MongoTemplate mongo;

    public AdminAnnouncement insert(AdminAnnouncement announcement) {
        return mongo.insert(announcement);
    }

    public AdminAnnouncement findByActorAndRequest(String actorId, String requestId) {
        return mongo.findOne(Query.query(Criteria.where("actorId").is(actorId).and("requestId").is(requestId)),
                AdminAnnouncement.class);
    }

    public AdminAnnouncement findById(String id) {
        return mongo.findById(id, AdminAnnouncement.class);
    }

    public OrganizerPage organizerPage(String eventId, String search, int page, int size) {
        Query query;
        if (eventId != null) {
            query = Query.query(Criteria.where("organizerEventId").is(eventId));
        } else {
            query = Query.query(Criteria.where("status").is(PENDING_REVIEW).and("organizerEventId").ne(null)
                    .and("target.group").ne("STAFF"));
        }
        if (!search.isEmpty()) {
            Pattern literal = Pattern.compile(Pattern.quote(search), Pattern.CASE_INSENSITIVE);
            query.addCriteria(new Criteria().orOperator(Criteria.where("eventTitle").regex(literal),
                    Criteria.where("payload.title").regex(literal), Criteria.where("payload.message").regex(literal)));
        }
        long total = mongo.count(query, AdminAnnouncement.class);
        var items = mongo.find(query.with(Sort.by(Sort.Direction.DESC, "submittedAt", "id"))
                .skip((long) page * size).limit(size), AdminAnnouncement.class);
        return new OrganizerPage(items, total);
    }

    public AdminAnnouncement transition(String id, String eventId, int revision, AdminAnnouncement.Status expected,
                                        Update update, AdminAnnouncement.ReviewEntry history) {
        var target = Criteria.where("id").is(id).and("revision").is(revision).and("status").is(expected);
        if (eventId == null) target.and("organizerEventId").ne(null).and("target.group").ne("STAFF");
        else target.and("organizerEventId").is(eventId);
        return mongo.findAndModify(Query.query(target), update.inc("revision", 1).push("history", history),
                FindAndModifyOptions.options().returnNew(true), AdminAnnouncement.class);
    }

    public record OrganizerPage(List<AdminAnnouncement> items, long total) {}

    public AdminAnnouncement claim() {
        Instant now = Instant.now();
        var eligible = new Criteria().orOperator(
                Criteria.where("status").is(QUEUED).and("availableAt").lte(now),
                Criteria.where("status").is(PROCESSING).and("leaseUntil").lte(now));
        return mongo.findAndModify(Query.query(eligible).with(Sort.by("createdAt", "id")),
                Update.update("status", PROCESSING).set("leaseToken", UUID.randomUUID().toString())
                        .set("leaseUntil", now.plus(LEASE)).inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), AdminAnnouncement.class);
    }

    public boolean renew(AdminAnnouncement job) {
        return updateOwned(job, Update.update("leaseUntil", Instant.now().plus(LEASE)));
    }

    public boolean sealSnapshot(AdminAnnouncement job, long count) {
        return updateOwned(job, Update.update("snapshotId", job.getLeaseToken()).set("recipientCount", count));
    }

    public void addRecipients(AdminAnnouncement job, List<String> userIds) {
        if (userIds.isEmpty()) return;
        var bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, AnnouncementRecipient.class);
        userIds.stream().distinct().forEach(id -> bulk.upsert(
                Query.query(Criteria.where("id").is(job.getLeaseToken() + ":" + id)),
                new Update().setOnInsert("announcementId", job.getId()).setOnInsert("snapshotId", job.getLeaseToken())
                        .setOnInsert("userId", id)));
        bulk.execute();
    }

    public long countStagedRecipients(AdminAnnouncement job) {
        return mongo.count(snapshot(job.getId(), job.getLeaseToken()), AnnouncementRecipient.class);
    }

    public void discardOldSnapshots(AdminAnnouncement job) {
        mongo.remove(Query.query(Criteria.where("announcementId").is(job.getId())
                .and("snapshotId").ne(job.getSnapshotId())), AnnouncementRecipient.class);
    }

    public List<AnnouncementRecipient> pendingRecipients(AdminAnnouncement job, String snapshotId, int size) {
        Query pending = snapshot(job.getId(), snapshotId).addCriteria(Criteria.where("deliveredAt").is(null));
        return mongo.find(pending.with(Sort.by("id")).limit(size), AnnouncementRecipient.class);
    }

    public void markDelivered(AnnouncementRecipient recipient) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(recipient.id()).and("deliveredAt").is(null)),
                Update.update("deliveredAt", Instant.now()), AnnouncementRecipient.class);
    }

    public long countStoredNotifications(String id) {
        return mongo.count(Query.query(Criteria.where("eventId").type(2)
                .regex("^" + Pattern.quote("announcement:" + id + ":"))), Notification.class);
    }

    public void complete(AdminAnnouncement job) {
        updateOwned(job, releaseLease(Update.update("status", COMPLETED).unset("errorCode")));
    }

    public void retry(AdminAnnouncement job) {
        updateOwned(job, releaseLease(Update.update("status", QUEUED)
                .set("availableAt", Instant.now().plusSeconds(1L << Math.min(job.getAttempts(), 10)))));
    }

    public void fail(AdminAnnouncement job, String code) {
        updateOwned(job, releaseLease(Update.update("status", FAILED).set("errorCode", code)));
    }

    private boolean updateOwned(AdminAnnouncement job, Update update) {
        Query owned = Query.query(Criteria.where("id").is(job.getId()).and("status").is(PROCESSING)
                .and("leaseToken").is(job.getLeaseToken()).and("leaseUntil").gt(Instant.now()));
        return mongo.updateFirst(owned, update, AdminAnnouncement.class).getMatchedCount() == 1;
    }

    private static Update releaseLease(Update update) {
        return update.unset("leaseToken").unset("leaseUntil");
    }

    private static Query snapshot(String id, String snapshotId) {
        return Query.query(Criteria.where("announcementId").is(id).and("snapshotId").is(snapshotId));
    }
}
