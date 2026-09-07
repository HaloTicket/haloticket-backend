package com.haloticket.notification_service.config;

import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.NotificationTemplateStatus;
import com.haloticket.notification_service.enums.NotificationType;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final NotificationTemplateRepository notificationTemplateRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Initializing sample data for Notification Service...");

        try {
            entityManager.createNativeQuery("ALTER TABLE notifications ALTER COLUMN template_id DROP NOT NULL;").executeUpdate();
            log.info("Successfully dropped NOT NULL constraint on notifications.template_id");
        } catch (Exception e) {
            log.info("template_id column already nullable: " + e.getMessage());
        }

        upsertTemplate("ntpl-ticket-confirmation-email",
                NotificationType.TICKET_CONFIRMATION,
                NotificationChannel.EMAIL,
                "Your HaloTicket order is confirmed",
                "Hi {{fullName}}, your booking {{bookingCode}} has been confirmed.");

        upsertTemplate("ntpl-event-reminder-push",
                NotificationType.EVENT_REMINDER,
                NotificationChannel.PUSH,
                "Event reminder",
                "Your event {{eventTitle}} starts at {{startTime}}.");

        upsertTemplate("ntpl-ekyc-result-email",
                NotificationType.EKYC_RESULT,
                NotificationChannel.EMAIL,
                "eKYC verification result",
                "Your eKYC verification status is {{status}}.");

        log.info("Notification Service sample data initialized successfully!");
    }

    private void upsertTemplate(String id, NotificationType type, NotificationChannel channel,
                                 String subject, String body) {
        if (notificationTemplateRepository.existsById(id)) {
            log.info("Template '{}' already exists. Skipping.", id);
            return;
        }
        NotificationTemplate template = NotificationTemplate.builder()
                .id(id)
                .type(type)
                .channel(channel)
                .subjectTemplate(subject)
                .bodyTemplate(body)
                .status(NotificationTemplateStatus.ACTIVE)
                .build();
        entityManager.persist(template);
        log.info("Created template '{}'.", id);
    }
}

