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
        if (notificationTemplateRepository.count() > 0) {
            log.info("Notification database already populated. Skipping initialization.");
            return;
        }

        log.info("Initializing sample data for Notification Service...");

        NotificationTemplate ticketConfirmation = NotificationTemplate.builder()
                .id("ntpl-ticket-confirmation-email")
                .type(NotificationType.TICKET_CONFIRMATION)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("Your HaloTicket order is confirmed")
                .bodyTemplate("Hi {{fullName}}, your booking {{bookingCode}} has been confirmed.")
                .status(NotificationTemplateStatus.ACTIVE)
                .build();
        entityManager.merge(ticketConfirmation);

        NotificationTemplate eventReminder = NotificationTemplate.builder()
                .id("ntpl-event-reminder-push")
                .type(NotificationType.EVENT_REMINDER)
                .channel(NotificationChannel.PUSH)
                .subjectTemplate("Event reminder")
                .bodyTemplate("Your event {{eventTitle}} starts at {{startTime}}.")
                .status(NotificationTemplateStatus.ACTIVE)
                .build();
        entityManager.merge(eventReminder);

        NotificationTemplate ekycResult = NotificationTemplate.builder()
                .id("ntpl-ekyc-result-email")
                .type(NotificationType.EKYC_RESULT)
                .channel(NotificationChannel.EMAIL)
                .subjectTemplate("eKYC verification result")
                .bodyTemplate("Your eKYC verification status is {{status}}.")
                .status(NotificationTemplateStatus.ACTIVE)
                .build();
        entityManager.merge(ekycResult);

        log.info("Notification Service sample data initialized successfully! Created notification templates.");
    }
}
