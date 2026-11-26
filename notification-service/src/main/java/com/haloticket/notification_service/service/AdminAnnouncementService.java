package com.haloticket.notification_service.service;

import com.haloticket.notification_service.dto.request.AnnouncementRequest;
import com.haloticket.notification_service.dto.response.AnnouncementResponse;
import com.haloticket.notification_service.entity.AdminAnnouncement;
import com.haloticket.notification_service.repository.AnnouncementStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminAnnouncementService {
    private final AnnouncementStore store;

    public AnnouncementResponse enqueue(String actorId, AnnouncementRequest input) {
        var request = input.validated();
        Instant now = Instant.now();
        var job = AdminAnnouncement.builder()
                .id(UUID.randomUUID().toString())
                .actorId(actorId)
                .requestId(request.requestId())
                .payload(request)
                .status(AdminAnnouncement.Status.QUEUED)
                .createdAt(now)
                .availableAt(now)
                .build();

        try {
            return response(store.insert(job));
        } catch (DuplicateKeyException collision) {
            var existing = store.findByActorAndRequest(actorId, request.requestId());
            if (existing == null) throw collision;
            if (!existing.getPayload().equals(request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
            }
            return response(existing);
        }
    }

    public AnnouncementResponse get(String id) {
        var job = store.findById(id);
        if (job == null || job.getOrganizerEventId() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ANNOUNCEMENT_NOT_FOUND");
        }
        return response(job);
    }

    private AnnouncementResponse response(AdminAnnouncement job) {
        long delivered = job.getSnapshotId() == null ? 0 : store.countStoredNotifications(job.getId());
        return new AnnouncementResponse(job.getId(), job.getStatus(), job.getRecipientCount(), delivered, job.getErrorCode());
    }
}
