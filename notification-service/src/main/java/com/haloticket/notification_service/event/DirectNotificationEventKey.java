package com.haloticket.notification_service.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DirectNotificationEventKey {
    private DirectNotificationEventKey() {}

    public static String sharedKey(String timestamp, String userId, String type, String title, String message,
                                   String variables) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{timestamp, userId, type, title, message, variables}) {
                if (field == null) {
                    digest.update((byte) 0);
                    continue;
                }
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update((value.length + ":").getBytes(StandardCharsets.US_ASCII));
                digest.update(value);
            }
            return "direct:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String kafkaRecordKey(ConsumerRecord<?, ?> record) {
        return "kafka:" + record.topic() + ":" + record.partition() + ":" + record.offset();
    }
}
