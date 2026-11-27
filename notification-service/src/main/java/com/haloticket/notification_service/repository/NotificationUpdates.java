package com.haloticket.notification_service.repository;

import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.enums.NotificationVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationUpdates {
    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt", "id");
    private static final FindAndModifyOptions RETURN_UPDATED = FindAndModifyOptions.options().returnNew(true);

    private final MongoTemplate mongo;

    public List<Notification> inbox(Collection<String> aliases, InboxChannel channel) {
        return mongo.find(visibleInbox(aliases, channel).with(NEWEST), Notification.class);
    }

    public Notification markRead(String id, Collection<String> aliases) {
        Query query = visibleInbox(aliases, InboxChannel.ALL).addCriteria(Criteria.where("id").is(id));
        return mongo.findAndModify(query, Update.update("readStatus", true), RETURN_UPDATED, Notification.class);
    }

    public Notification markUnread(String id, Collection<String> aliases) {
        Query query = visibleInbox(aliases, InboxChannel.ALL).addCriteria(Criteria.where("id").is(id));
        return mongo.findAndModify(query, Update.update("readStatus", false), RETURN_UPDATED, Notification.class);
    }

    public List<Notification> markAllRead(Collection<String> aliases, Instant startedAt, InboxChannel channel) {
        var eligible = new Criteria().orOperator(Criteria.where("createdAt").lt(startedAt),
                Criteria.where("createdAt").is(null));
        Query query = visibleInbox(aliases, channel).addCriteria(eligible);
        mongo.updateMulti(query, Update.update("readStatus", true), Notification.class);
        return inbox(aliases, channel);
    }

    public List<Notification> markAllRead(Collection<String> aliases, Instant startedAt) {
        return markAllRead(aliases, startedAt, InboxChannel.PUBLIC);
    }

    public boolean softDelete(String id, Collection<String> aliases) {
        Query owned = Query.query(Criteria.where("id").is(id)
                .and("userId").in(aliases).ne("ADMIN"));
        var changed = mongo.updateFirst(Query.of(owned).addCriteria(Criteria.where("deletedAt").is(null)),
                Update.update("deletedAt", Instant.now()), Notification.class);
        return changed.getMatchedCount() != 0 || mongo.exists(owned, Notification.class);
    }

    public Notification setStatus(String id, NotificationStatus status) {
        Update update = Update.update("status", status);
        if (status == NotificationStatus.SENT) {
            update.set("sentAt", Instant.now());
        }
        return mongo.findAndModify(Query.query(Criteria.where("id").is(id)
                        .and("visibility").ne(NotificationVisibility.STAFF)), update,
                RETURN_UPDATED, Notification.class);
    }

    private static Query visibleInbox(Collection<String> aliases, InboxChannel channel) {
        var criteria = Criteria.where("userId").in(aliases).and("deletedAt").is(null);
        return Query.query(switch (channel) {
            case ALL -> criteria;
            case STAFF -> criteria.and("visibility").is(NotificationVisibility.STAFF);
            case PUBLIC -> criteria.and("visibility").in(NotificationVisibility.PUBLIC, null);
        });
    }
}
