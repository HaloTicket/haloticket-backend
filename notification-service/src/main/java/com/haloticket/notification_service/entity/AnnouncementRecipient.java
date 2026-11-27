package com.haloticket.notification_service.entity;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;

@Document("notification_announcement_recipients")
public record AnnouncementRecipient(@MongoId(FieldType.STRING) String id, String announcementId,
                                    String snapshotId, String userId, Instant deliveredAt) {}
