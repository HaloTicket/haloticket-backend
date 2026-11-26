package com.haloticket.notification_service.config;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import com.mongodb.client.model.IndexOptions;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "notification.mongo.initialize", matchIfMissing = true)
@RequiredArgsConstructor
public class DataInitializer implements InitializingBean {
    private final MongoTemplate mongo;

    @Override
    public void afterPropertiesSet() {
        var notifications = mongo.getCollection("notifications");
        notifications.createIndex(new Document("userId", 1).append("deletedAt", 1).append("createdAt", -1).append("_id", -1));
        notifications.createIndex(new Document("userId", 1).append("visibility", 1).append("deletedAt", 1)
                .append("createdAt", -1).append("_id", -1));
        notifications.createIndex(new Document("eventId", 1), new IndexOptions().unique(true)
                .partialFilterExpression(new Document("eventId", new Document("$type", "string"))));
        mongo.getCollection("notification_templates")
                .createIndex(new Document("type", 1).append("channel", 1).append("status", 1));

        var jobs = mongo.getCollection("notification_announcements");
        jobs.createIndex(new Document("actorId", 1).append("requestId", 1), new IndexOptions().unique(true));
        jobs.createIndex(new Document("status", 1).append("availableAt", 1));
        jobs.createIndex(new Document("status", 1).append("leaseUntil", 1));
        jobs.createIndex(new Document("organizerEventId", 1).append("submittedAt", -1).append("_id", -1));
        jobs.createIndex(new Document("status", 1).append("submittedAt", -1).append("_id", -1));
        mongo.getCollection("notification_announcement_recipients").createIndex(
                new Document("announcementId", 1).append("snapshotId", 1).append("deliveredAt", 1).append("_id", 1));

        seed("ntpl-ticket-confirmation-email", NotificationType.TICKET_CONFIRMATION, NotificationChannel.EMAIL,
                "HaloTicket - Thanh to\u00e1n th\u00e0nh c\u00f4ng \u0111\u01a1n {{bookingCode}}",
                "Hi {{fullName}}, your booking {{bookingCode}} has been confirmed.");
        seed("ntpl-event-reminder-push", NotificationType.EVENT_REMINDER, NotificationChannel.PUSH,
                "Event reminder", "Your event {{eventTitle}} starts at {{startTime}}.");
        seed("ntpl-ekyc-result-email", NotificationType.EKYC_RESULT, NotificationChannel.EMAIL,
                "eKYC verification result", "Your eKYC verification status is {{status}}.");
    }

    private void seed(String id, NotificationType type, NotificationChannel channel, String subject, String body) {
        Instant now = Instant.now();
        Query byId = Query.query(Criteria.where("id").is(id));
        var defaults = new Update().setOnInsert("type", type).setOnInsert("channel", channel)
                .setOnInsert("subjectTemplate", subject).setOnInsert("bodyTemplate", body)
                .setOnInsert("status", NotificationTemplateStatus.ACTIVE)
                .setOnInsert("createdAt", now).setOnInsert("updatedAt", now);
        try {
            mongo.upsert(byId, defaults, NotificationTemplate.class);
        } catch (DuplicateKeyException collision) {
            if (!mongo.exists(byId, NotificationTemplate.class)) throw collision;
        }
    }
}
