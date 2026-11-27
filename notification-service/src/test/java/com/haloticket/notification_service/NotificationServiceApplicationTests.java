package com.haloticket.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "notification.mongo.initialize=false",
        "notification.announcements.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.mongodb.uri=mongodb://127.0.0.1:1/notification-context-test",
        "eureka.client.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
class NotificationServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
