package com.haloticket.notification_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.notification_service.client.RecipientDirectory;
import com.haloticket.notification_service.client.TicketOwnerClient.Ownership;
import com.haloticket.notification_service.client.AnnouncementScanClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient;
import com.haloticket.notification_service.client.EventAnnouncementScopeClient.EventScope;
import com.haloticket.notification_service.config.DataInitializer;
import com.haloticket.notification_service.dto.request.AnnouncementRequest;
import com.haloticket.notification_service.dto.request.AnnouncementTarget;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Audience;
import com.haloticket.notification_service.dto.request.AnnouncementRequest.Kind;
import com.haloticket.notification_service.dto.request.OrganizerAnnouncementRequest.*;
import com.haloticket.notification_service.dto.response.AnnouncementScan;
import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.entity.AnnouncementRecipient;
import com.haloticket.notification_service.entity.Notification;
import com.haloticket.notification_service.entity.NotificationTemplate;
import com.haloticket.notification_service.enums.NotificationChannel;
import com.haloticket.notification_service.enums.InboxChannel;
import com.haloticket.notification_service.enums.NotificationVisibility;
import com.haloticket.notification_service.enums.AnnouncementCategory;
import com.haloticket.notification_service.enums.NotificationSource;
import com.haloticket.notification_service.enums.NotificationPriority;
import com.haloticket.notification_service.enums.NotificationStatus;
import com.haloticket.notification_service.event.NotificationEvent;
import com.haloticket.notification_service.repository.AnnouncementStore;
import com.haloticket.notification_service.repository.NotificationRepository;
import com.haloticket.notification_service.repository.NotificationTemplateRepository;
import com.haloticket.notification_service.repository.NotificationUpdates;
import com.haloticket.notification_service.service.AdminAnnouncementService;
import com.haloticket.notification_service.service.AdminAnnouncementWorker;
import com.haloticket.notification_service.service.OrganizerAnnouncementService;
import com.haloticket.notification_service.service.OrganizerRecipients;
import com.haloticket.notification_service.service.impl.NotificationServiceImpl;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "NOTIFICATION_TEST_MONGODB_URI", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationMongoIntegrationTest {
    private MongoClient client;
    private MongoTemplate mongo;
    private NotificationRepository repository;
    private NotificationUpdates inbox;
    private AnnouncementStore jobs;
    private AdminAnnouncementService announcements;
    private RecipientDirectory directory;
    private SimpMessagingTemplate stomp;
    private NotificationServiceImpl notifications;
    private AdminAnnouncementWorker worker;

    @BeforeAll
    void connect() {
        client = MongoClients.create(System.getenv("NOTIFICATION_TEST_MONGODB_URI"));
        mongo = new MongoTemplate(client, "notification_test_" + UUID.randomUUID().toString().replace("-", ""));
        new DataInitializer(mongo).afterPropertiesSet();

        var factory = new MongoRepositoryFactory(mongo);
        repository = factory.getRepository(NotificationRepository.class);
        var templates = factory.getRepository(NotificationTemplateRepository.class);
        inbox = new NotificationUpdates(mongo);
        jobs = new AnnouncementStore(mongo);
        announcements = new AdminAnnouncementService(jobs);
        directory = mock(RecipientDirectory.class);
        stomp = mock(SimpMessagingTemplate.class);
        notifications = new NotificationServiceImpl(repository, templates, new ObjectMapper(), inbox, directory, stomp);
        worker = new AdminAnnouncementWorker(jobs, directory, notifications, new OrganizerRecipients(directory));
    }

    @BeforeEach
    void clearDocuments() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), AnnouncementRecipient.class);
        mongo.remove(new Query(), AdminAnnouncement.class);
        reset(directory, stomp);
    }

    @AfterAll
    void close() {
        if (client == null) return;
        try {
            if (mongo != null) mongo.getDb().drop();
        } finally {
            client.close();
        }
    }

    @Test
    void existingStringIdsDatesAndCustomTemplatesSurviveRestart() {
        String fixedId = "ntpl-ticket-confirmation-email";
        mongo.updateFirst(id(fixedId), Update.update("subjectTemplate", "Edited subject"), NotificationTemplate.class);
        new DataInitializer(mongo).afterPropertiesSet();
        assertThat(mongo.findById(fixedId, NotificationTemplate.class).getSubjectTemplate()).isEqualTo("Edited subject");

        String legacyId = "507f1f77bcf86cd799439011";
        Instant utc = Instant.parse("2026-11-25T03:15:01.123Z");
        mongo.getCollection("notifications").insertOne(new Document("_id", legacyId)
                .append("userId", "kc-owner").append("templateId", fixedId).append("createdAt", Date.from(utc))
                .append("channel", "EMAIL").append("status", "SENT").append("readStatus", null));
        assertThat(repository.findById(legacyId)).isPresent();
        var item = notifications.getUserNotifications(List.of("kc-owner", "u-owner")).getFirst();
        assertThat(item.getTemplateId()).isEqualTo(fixedId);
        assertThat(item.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-11-25T10:15:01.123"));
        assertThat(item.getReadStatus()).isNull();
        assertThat(item.getPriority()).isEqualTo(NotificationPriority.NORMAL);
    }

    @Test
    void concurrentReplayCannotDuplicateOrResurrectADeletedItem() throws Exception {
        when(directory.canonicalIdOrAlias("kc-owner")).thenReturn("u-owner");
        var event = new NotificationEvent("event-1", null, "kc-owner", "a@example.test", NotificationChannel.EMAIL,
                Map.of(), null);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = calls.submit(() -> notifications.processEvent(event));
            var second = calls.submit(() -> notifications.processEvent(event));
            assertThat(first.get().getId()).isEqualTo(second.get().getId());
        }

        var item = notifications.getUserNotifications(List.of("u-owner")).getFirst();
        assertThatThrownBy(() -> notifications.delete(item.getId(), List.of("u-other")))
                .hasMessageContaining("Notification not found");
        notifications.delete(item.getId(), List.of("kc-owner", "u-owner"));
        notifications.delete(item.getId(), List.of("u-owner"));
        assertThat(notifications.processEvent(event).getId()).isEqualTo(item.getId());
        assertThat(notifications.getUserNotifications(List.of("u-owner"))).isEmpty();
        assertThat(repository.count()).isEqualTo(1);
        verify(stomp).convertAndSend(eq("/topic/user-notifications/u-owner"), any(Object.class));
        var document = mongo.getCollection("notifications").find(new Document("_id", item.getId())).first();
        assertThat(document.get("createdAt")).isInstanceOf(Date.class);
        assertThat(document.get("deletedAt")).isInstanceOf(Date.class);
        assertThat(document).containsEntry("templateId", null);
    }

    @Test
    void readAllUsesBothAliasesAndLeavesNewAndDeletedItemsAlone() {
        Instant cutoff = Instant.parse("2026-11-25T03:00:00Z");
        insert("old-subject", "kc-owner", cutoff.minusMillis(1), null);
        insert("old-account", "u-owner", cutoff.minusMillis(1), null);
        insert("new-account", "u-owner", cutoff.plusMillis(1), null);
        insert("deleted", "u-owner", cutoff.minusMillis(1), cutoff);
        insert("admin", "ADMIN", cutoff.minusMillis(1), null);

        var result = inbox.markAllRead(List.of("u-owner", "kc-owner"), cutoff);
        assertThat(result).extracting(Notification::getId).containsExactly("new-account", "old-subject", "old-account");
        assertThat(result).extracting(Notification::getReadStatus).containsExactly(null, true, true);
        assertThat(inbox.markRead("deleted", List.of("u-owner"))).isNull();
        assertThat(inbox.softDelete("admin", List.of("ADMIN"))).isFalse();
        assertThat(repository.findById("deleted").orElseThrow().getReadStatus()).isNull();
    }

    @Test
    void emailAndReadUpdatesDoNotOverwriteOneAnother() throws Exception {
        insert("item", "u-owner", Instant.now(), null);
        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = calls.submit(() -> inbox.markRead("item", List.of("u-owner")));
            var sent = calls.submit(() -> notifications.markAsSent("item"));
            assertThat(read.get().getReadStatus()).isTrue();
            assertThat(sent.get().getStatus()).isEqualTo(NotificationStatus.SENT);
        }
        var saved = repository.findById("item").orElseThrow();
        assertThat(saved.getReadStatus()).isTrue();
        assertThat(saved.getSentAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void allAccountsArePagedAndRequestReplayIsScopedToTheAdmin() {
        var request = request(new Audience(Kind.ALL, null, null));
        var job = announcements.enqueue("admin-1", request);
        assertThat(announcements.enqueue("admin-1", request).id()).isEqualTo(job.id());
        assertThatThrownBy(() -> announcements.enqueue("admin-1",
                new AnnouncementRequest(request.requestId(), "Different", request.message(), request.priority(), request.audience())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        var firstPage = IntStream.range(0, 200).mapToObj(i -> "user-%03d".formatted(i)).toList();
        when(directory.activeAccounts(any(), eq(""), eq(200))).thenReturn(firstPage);
        when(directory.activeAccounts(any(), eq("user-199"), eq(200))).thenReturn(List.of("user-200"));
        worker.runNext();
        var result = announcements.get(job.id());
        assertThat(result.status()).isEqualTo(AdminAnnouncement.Status.COMPLETED);
        assertThat(result.recipientCount()).isEqualTo(201);
        assertThat(result.deliveredCount()).isEqualTo(201);
        assertThat(repository.count()).isEqualTo(201);
        var personal = notifications.getUserNotifications(List.of("user-200")).getFirst();
        assertThat(personal.getPriority()).isEqualTo(NotificationPriority.URGENT);
        assertThat(personal.getChannel()).isEqualTo(NotificationChannel.PUSH);
        assertThat(personal.getUserId()).isEqualTo("user-200");
        verify(stomp, never()).convertAndSend(eq("/topic/admin-notifications"), any(Object.class));
        assertThat(announcements.enqueue("admin-2", request).id()).isNotEqualTo(job.id());
    }

    @Test
    void reclaimedLeaseResumesFrozenRecipientsAndCountsAnAlreadyStoredDelivery() {
        var request = request(new Audience(Kind.EVENT_ORGANIZING_TEAM, null, "event"));
        var job = announcements.enqueue("admin-1", request);
        var claimed = jobs.claim();
        jobs.addRecipients(claimed, List.of("owner", "member", "member"));
        assertThat(jobs.sealSnapshot(claimed, 2)).isTrue();
        notifications.deliverAnnouncement(job.id(), "owner", "Title", "Message", NotificationPriority.URGENT);
        mongo.updateFirst(id(job.id()), Update.update("leaseUntil", Instant.now().minusSeconds(1)), AdminAnnouncement.class);

        assertThat(announcements.get(job.id()).deliveredCount()).isEqualTo(1);
        worker.runNext();

        assertThat(announcements.get(job.id()).status()).isEqualTo(AdminAnnouncement.Status.COMPLETED);
        assertThat(announcements.get(job.id()).deliveredCount()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(2);
        verifyNoInteractions(directory);
        verify(stomp).convertAndSend(eq("/topic/user-notifications/owner"), any(Object.class));
        verify(stomp).convertAndSend(eq("/topic/user-notifications/member"), any(Object.class));
    }

    @Test
    void eventTeamFiltersAccountsThroughTheActiveDirectoryAndUserAudienceStaysPersonal() {
        var team = announcements.enqueue("admin", request(new Audience(Kind.EVENT_ORGANIZING_TEAM, null, "event")));
        when(directory.eventTeam(eq("event"), any(), eq(""), eq(200))).thenReturn(List.of("missing", "owner"));
        when(directory.activeMatches(any(), eq(List.of("missing", "owner")))).thenReturn(List.of("owner"));
        worker.runNext();
        assertThat(announcements.get(team.id()).recipientCount()).isEqualTo(1);
        assertThat(notifications.getUserNotifications(List.of("missing"))).isEmpty();

        var personal = announcements.enqueue("admin", request(new Audience(Kind.USER, "member", null)));
        when(directory.activeAccount(eq("member"), any())).thenReturn("member");
        worker.runNext();
        assertThat(announcements.get(personal.id()).deliveredCount()).isEqualTo(1);
        assertThat(notifications.getUserNotifications(List.of("owner"))).hasSize(1);
        assertThat(notifications.getUserNotifications(List.of("member"))).hasSize(1);
    }

    @Test
    void organizerWorkflowHasRevisionHistoryAndCannotDeliverBeforeApprovalOrDuplicateAfterLeaseRecovery() {
        var events = mock(EventAnnouncementScopeClient.class);
        when(events.scope("event", "Bearer caller")).thenReturn(new EventScope("event", "Trusted event", "owner", "MANAGER"));
        var scanner = mock(AnnouncementScanClient.class);
        when(scanner.scan(anyString(), anyString())).thenReturn(AnnouncementScan.unavailable());
        var organizer = new OrganizerAnnouncementService(jobs, events, scanner, new OrganizerRecipients(directory));
        when(directory.ticketOwners("event", "owner", "", "", "", 200))
                .thenReturn(List.of(new Ownership("buyer", true, false)));
        when(directory.activeBuyerMatches(any(), eq(List.of("buyer")))).thenReturn(List.of("buyer"));
        var request = new Submit(UUID.randomUUID(), "Original [title]", "Original message", AnnouncementCategory.PROMOTION);
        var submitted = organizer.submit("event", "Bearer caller", request);
        assertThat(submitted.status()).isEqualTo(AdminAnnouncement.Status.PENDING_REVIEW);
        assertThat(submitted.revision()).isEqualTo(1);
        assertThat(submitted.eventTitle()).isEqualTo("Trusted event");
        assertThat(organizer.pending("[TITLE]", 0, 20).total()).isEqualTo(1);
        assertThat(organizer.pending(".*", 0, 20).total()).isZero();
        worker.runNext();
        verifyNoInteractions(stomp);
        assertThat(repository.count()).isZero();

        assertThatThrownBy(() -> organizer.review(submitted.id(), "admin", new Review(1, Action.REQUEST_CHANGES, "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        var changes = organizer.review(submitted.id(), "admin", new Review(1, Action.REQUEST_CHANGES, "Clarify date"));
        assertThat(changes.reviewReason()).isEqualTo("Clarify date");
        assertThat(organizer.pending("", 0, 20).total()).isZero();
        assertThatThrownBy(() -> organizer.resubmit("event", submitted.id(), "Bearer caller",
                new Resubmit(1, "Stale", "Message", AnnouncementCategory.REMINDER)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        var revised = organizer.resubmit("event", submitted.id(), "Bearer caller",
                new Resubmit(changes.revision(), "Revised title", "Revised message", AnnouncementCategory.REMINDER));
        assertThat(revised.revision()).isEqualTo(3);
        assertThat(revised.reviewReason()).isNull();
        assertThat(organizer.submit("event", "Bearer caller", request).id()).isEqualTo(submitted.id());
        verify(scanner, times(2)).scan(anyString(), anyString());
        assertThatThrownBy(() -> organizer.review(submitted.id(), "admin", new Review(1, Action.APPROVE, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        worker.runNext();
        assertThat(repository.count()).isZero();

        var approved = organizer.review(submitted.id(), "admin", new Review(revised.revision(), Action.APPROVE, null));
        assertThat(approved.status()).isEqualTo(AdminAnnouncement.Status.QUEUED);
        var claimed = jobs.claim();
        assertThat(claimed.getHistory()).extracting(AdminAnnouncement.ReviewEntry::reason)
                .containsExactly(null, "Clarify date", null, null);
        jobs.addRecipients(claimed, List.of("buyer", "buyer"));
        assertThat(jobs.countStagedRecipients(claimed)).isEqualTo(1);
        assertThat(jobs.sealSnapshot(claimed, 1)).isTrue();
        notifications.deliverOrganizerAnnouncement(claimed, "buyer");
        mongo.updateFirst(id(submitted.id()), Update.update("leaseUntil", Instant.now().minusSeconds(1)), AdminAnnouncement.class);
        worker.runNext();

        var finished = organizer.list("event", "Bearer caller", 0, 20).items().getFirst();
        assertThat(finished.status()).isEqualTo(AdminAnnouncement.Status.COMPLETED);
        assertThat(finished.recipientCount()).isEqualTo(1);
        assertThat(finished.deliveredCount()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        var item = notifications.getUserNotifications(List.of("buyer")).getFirst();
        assertThat(item.getSource()).isEqualTo(NotificationSource.ORGANIZER);
        assertThat(item.getType()).isEqualTo("ORGANIZER_ANNOUNCEMENT");
        assertThat(item.getEventId()).isEqualTo("event");
        assertThat(item.getEventTitle()).isEqualTo("Trusted event");
        assertThat(item.getCategory()).isEqualTo(AnnouncementCategory.REMINDER);
        verify(stomp).convertAndSend(eq("/topic/user-notifications/buyer"), any(Object.class));
    }

    @Test
    void approvedBuyerAudienceFiltersInactiveAccountsAndRejectedAnnouncementsRemainUnclaimable() {
        var events = mock(EventAnnouncementScopeClient.class);
        when(events.scope("event", "Bearer caller")).thenReturn(new EventScope("event", "Event", "owner", "OWNER"));
        var scanner = mock(AnnouncementScanClient.class);
        var organizer = new OrganizerAnnouncementService(jobs, events, scanner, new OrganizerRecipients(directory));
        when(directory.ticketOwners("event", "owner", "", "", "", 200))
                .thenReturn(List.of(new Ownership("active", true, false), new Ownership("inactive", false, true)));
        when(directory.activeBuyerMatches(any(), eq(List.of("active", "inactive")))).thenReturn(List.of("active"));
        var rejected = organizer.submit("event", "Bearer caller",
                new Submit(UUID.randomUUID(), "Rejected", "Message", AnnouncementCategory.EVENT_UPDATE));
        assertThat(organizer.review(rejected.id(), "admin", new Review(1, Action.REJECT, "Policy")).status())
                .isEqualTo(AdminAnnouncement.Status.REJECTED);
        assertThat(jobs.findById(rejected.id()).getHistory().getLast().reason()).isEqualTo("Policy");
        var accepted = organizer.submit("event", "Bearer caller",
                new Submit(UUID.randomUUID(), "Accepted", "Message", AnnouncementCategory.EVENT_UPDATE));
        organizer.review(accepted.id(), "admin", new Review(1, Action.APPROVE, null));
        worker.runNext();
        assertThat(jobs.findById(rejected.id()).getStatus()).isEqualTo(AdminAnnouncement.Status.REJECTED);
        assertThat(jobs.findById(accepted.id()).getStatus()).isEqualTo(AdminAnnouncement.Status.COMPLETED);
        assertThat(notifications.getUserNotifications(List.of("inactive"))).isEmpty();
        assertThat(notifications.getUserNotifications(List.of("active"))).hasSize(1);
    }

    @Test
    void staffSnapshotsAreRevalidatedAndStaffRemainsPrivateInInboxAndStomp() {
        var events = mock(EventAnnouncementScopeClient.class);
        when(events.scope("event", "Bearer caller")).thenReturn(new EventScope("event", "Event", "owner", "OWNER"));
        var scanner = mock(AnnouncementScanClient.class);
        var organizer = new OrganizerAnnouncementService(jobs, events, scanner, new OrganizerRecipients(directory));
        var target = new AnnouncementTarget(AnnouncementTarget.Group.STAFF, AnnouncementTarget.Scope.MEMBER,
                null, null, null, "member");
        when(directory.announcementStaff(eq("event"), eq("owner"), any(), isNull(), eq("member"), eq(""), eq(200)))
                .thenReturn(List.of("member"));
        when(directory.activeMatches(any(), eq(List.of("member")))).thenReturn(List.of("member"), List.of(),
                List.of("member"), List.of("member"));
        var stale = organizer.submit("event", "Bearer caller",
                new Submit(UUID.randomUUID(), "Stale", "Message", AnnouncementCategory.INTERNAL, target, 1L));
        worker.runNext();
        assertThat(jobs.findById(stale.id()).getErrorCode()).isEqualTo("RECIPIENT_COUNT_CHANGED");
        assertThat(repository.count()).isZero();

        var sent = organizer.submit("event", "Bearer caller",
                new Submit(UUID.randomUUID(), "Private", "Message", AnnouncementCategory.INTERNAL, target, 1L));
        assertThat(organizer.pending("", 0, 20).total()).isZero();
        assertThatThrownBy(() -> organizer.review(sent.id(), "admin", new Review(1, Action.APPROVE, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        worker.runNext();
        insert("legacy", "member", Instant.now().minusSeconds(1), null);
        var aliases = List.of("member");
        assertThat(inbox.inbox(aliases, InboxChannel.ALL)).hasSize(2);
        assertThat(inbox.inbox(aliases, InboxChannel.PUBLIC)).extracting(Notification::getId).containsExactly("legacy");
        var item = notifications.getUserNotifications(aliases, InboxChannel.STAFF).getFirst();
        assertThat(item.getVisibility()).isEqualTo(NotificationVisibility.STAFF);
        assertThat(item.getEventId()).isEqualTo("event");
        assertThat(item.getReadStatus()).isFalse();
        inbox.markAllRead(aliases, Instant.now().plusSeconds(1), InboxChannel.PUBLIC);
        assertThat(inbox.inbox(aliases, InboxChannel.STAFF).getFirst().getReadStatus()).isFalse();
        assertThat(notifications.markAsRead(item.getId(), aliases).getReadStatus()).isTrue();
        assertThat(inbox.inbox(List.of("other"), InboxChannel.ALL)).isEmpty();
        verify(stomp).convertAndSend(eq("/topic/staff-notifications/member"), any(Object.class));
        verify(stomp, never()).convertAndSend(eq("/topic/user-notifications/member"), any(Object.class));
        verifyNoInteractions(scanner);
    }

    private void insert(String id, String userId, Instant createdAt, Instant deletedAt) {
        repository.insert(Notification.builder().id(id).userId(userId).createdAt(createdAt).deletedAt(deletedAt).build());
    }

    private static Query id(String id) {
        return Query.query(Criteria.where("id").is(id));
    }

    private static AnnouncementRequest request(Audience audience) {
        return new AnnouncementRequest(UUID.randomUUID().toString(), " Title ", " Message ", NotificationPriority.URGENT, audience);
    }
}
