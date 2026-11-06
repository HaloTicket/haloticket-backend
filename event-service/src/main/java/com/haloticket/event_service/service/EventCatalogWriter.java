package com.haloticket.event_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.authz.EventPermissions;
import com.haloticket.event_service.authz.Permission;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.enums.TicketSyncStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The short database transactions of event saving. A separate bean so that {@link Transactional} really applies (the
 * orchestrating EventServiceImpl is not transactional and calls ticket-service only after this transaction has committed).
 */
@Component
@RequiredArgsConstructor
public class EventCatalogWriter {

    /** The saved event and the approval status it had before this save. */
    public record Saved(Event event, EventApprovalStatus oldApprovalStatus) {}

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final EventOrganizerRepository eventOrganizerRepository;
    private final EventPermissions eventPermissions;
    private final ObjectMapper objectMapper;

    /** Who may change an event: OWNER or ADMIN of the event (ACTIVE assignment) or a platform admin. */
    private boolean mayEdit(Event event, String userId) {
        return eventPermissions.forUser(userId, event.getId(), EventAccessService.isPlatformAdmin())
                .has(Permission.EVENT_EDIT);
    }

    @Transactional
    public Saved save(EventCreateRequest request, String uid) {
        Event eventToSave;
        if (request.getEventId() != null && !request.getEventId().isBlank()) {
            eventToSave = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
            if (!mayEdit(eventToSave, uid)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EVENT_OWNERSHIP_REQUIRED");
            }
        } else {
            eventToSave = new Event();
        }

        // create or update Organizer profile
        List<EventOrganizer> organizers = new ArrayList<>();
        EventOrganizer organizer = eventOrganizerRepository
                .findFirstByUserIdOrderByCreatedAtDesc(uid)
                .orElseGet(() -> EventOrganizer.builder()
                        .userId(uid)
                        .name(request.getOrganizerName())
                        .description(request.getOrganizerInfo())
                        .avatarUrl(request.getOrganizerLogo())
                        .build());

        organizer.setUserId(uid);
        if (request.getOrganizerName() != null && !request.getOrganizerName().trim().isEmpty()) {
            organizer.setName(request.getOrganizerName());
        }
        if (request.getOrganizerInfo() != null)
            organizer.setDescription(request.getOrganizerInfo());
        if (request.getOrganizerLogo() != null)
            organizer.setAvatarUrl(request.getOrganizerLogo());

        organizer = eventOrganizerRepository.save(organizer);
        organizers.add(organizer);

        // auto create OWNER StaffAssignment, in the same save as the event
        if (eventToSave.getId() == null) {
            StaffAssignment staff = StaffAssignment.builder()
                    .event(eventToSave)
                    .userId(uid)
                    .name(request.getOrganizerName())
                    .role(StaffRole.OWNER)
                    .status(StaffAssignmentStatus.ACTIVE)
                    .build();
            if (eventToSave.getStaffAssignments() == null) {
                eventToSave.setStaffAssignments(new ArrayList<>());
            }
            eventToSave.getStaffAssignments().add(staff);
        }

        // find category
        EventCategory category = null;
        if (request.getCategoryId() != null) {
            category = eventCategoryRepository.findById(request.getCategoryId()).orElse(null);
        }
        if (category == null && request.getCategoryName() != null) {
            category = eventCategoryRepository.findByNameContainingIgnoreCase(request.getCategoryName()).orElse(null);
        }

        String eventTitle = (request.getTitle() != null && !request.getTitle().trim().isEmpty())
                ? request.getTitle()
                : "";

        eventToSave.setTitle(eventTitle);
        eventToSave.setDescription(request.getDescription());
        eventToSave.setLocationType(request.getLocationType());
        eventToSave.setLocationName(request.getLocationName());
        eventToSave.setCity(request.getCity());
        eventToSave.setWard(request.getWard());
        eventToSave.setAddress(request.getAddress());
        if (request.getThumbnailUrl() != null)
            eventToSave.setThumbnailUrl(request.getThumbnailUrl());
        if (request.getBannerUrl() != null)
            eventToSave.setBannerUrl(request.getBannerUrl());
        if (request.getGalleryImages() != null) {
            if (eventToSave.getGalleryImages() == null) {
                eventToSave.setGalleryImages(new ArrayList<>(request.getGalleryImages()));
            } else {
                eventToSave.getGalleryImages().clear();
                eventToSave.getGalleryImages().addAll(request.getGalleryImages());
            }
        }
        LocalDateTime startEvt = request.getStartTime() != null ? request.getStartTime()
                : LocalDateTime.now().plusDays(30);
        LocalDateTime endEvt = request.getEndTime() != null ? request.getEndTime()
                : LocalDateTime.now().plusDays(30).plusHours(4);
        if (endEvt.isBefore(startEvt) || endEvt.isEqual(startEvt)) {
            throw new IllegalArgumentException(
                    "Thời gian kết thúc sự kiện phải diễn ra sau thời gian bắt đầu sự kiện!");
        }
        eventToSave.setStartTime(startEvt);
        eventToSave.setEndTime(endEvt);
        eventToSave.setIsPrivate(request.getIsPrivate());
        eventToSave.setConfirmationMessage(request.getConfirmationMessage());
        eventToSave.setEnableCustomQuestions(request.getEnableCustomQuestions());
        eventToSave.setQuestionScope(request.getQuestionScope());

        if (request.getShowtimes() != null && !request.getShowtimes().isEmpty()) {
            if (eventToSave.getShowtimes() == null) {
                eventToSave.setShowtimes(new ArrayList<>());
            }

            Map<String, EventShowtime> existingShowtimes = new java.util.HashMap<>();
            for (EventShowtime st : eventToSave.getShowtimes()) {
                existingShowtimes.put(st.getId(), st);
            }

            List<EventShowtime> updatedShowtimes = new ArrayList<>();

            for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
                EventShowtime st = null;
                if (stReq.getId() != null && existingShowtimes.containsKey(stReq.getId())) {
                    st = existingShowtimes.get(stReq.getId());
                } else {
                    // fallback to name matching
                    for (EventShowtime existing : existingShowtimes.values()) {
                        if (existing.getName() != null && existing.getName().equals(stReq.getName())) {
                            st = existing;
                            break;
                        }
                    }
                }

                if (st != null) {
                    st.setName(stReq.getName());
                    st.setStartTime(stReq.getStartTime() != null ? stReq.getStartTime() : startEvt);
                    st.setEndTime(stReq.getEndTime() != null ? stReq.getEndTime() : endEvt);
                    st.setSaleStartTime(stReq.getSaleStartTime());
                    st.setSaleEndTime(stReq.getSaleEndTime());
                } else {
                    st = EventShowtime.builder()
                            .event(eventToSave)
                            .name(stReq.getName())
                            .startTime(stReq.getStartTime() != null ? stReq.getStartTime() : startEvt)
                            .endTime(stReq.getEndTime() != null ? stReq.getEndTime() : endEvt)
                            .saleStartTime(stReq.getSaleStartTime())
                            .saleEndTime(stReq.getSaleEndTime())
                            .build();
                }
                updatedShowtimes.add(st);
            }
            eventToSave.getShowtimes().clear();
            eventToSave.getShowtimes().addAll(updatedShowtimes);
        }
        Double minTicketPrice = null;
        boolean flatShowtimeTickets = false;
        try {
            if (request.getTickets() != null && !request.getTickets().isEmpty()) {
                eventToSave.setTicketsData(objectMapper.writeValueAsString(request.getTickets()));
                for (EventCreateRequest.TicketCreateRequest t : request.getTickets()) {
                    if (t != null && t.getPrice() != null) {
                        if (minTicketPrice == null || t.getPrice() < minTicketPrice) {
                            minTicketPrice = t.getPrice();
                        }
                    }
                }
            } else if (request.getShowtimes() != null) {
                List<EventCreateRequest.TicketCreateRequest> allStTickets = new ArrayList<>();
                for (EventCreateRequest.EventShowtimeRequest st : request.getShowtimes()) {
                    if (st.getTickets() != null && !st.getTickets().isEmpty()) {
                        allStTickets.addAll(st.getTickets());
                        for (EventCreateRequest.TicketCreateRequest t : st.getTickets()) {
                            if (t != null && t.getPrice() != null) {
                                if (minTicketPrice == null || t.getPrice() < minTicketPrice) {
                                    minTicketPrice = t.getPrice();
                                }
                            }
                        }
                    }
                }
                if (!allStTickets.isEmpty()) {
                    eventToSave.setTicketsData(objectMapper.writeValueAsString(allStTickets));
                    flatShowtimeTickets = true;
                }
            }
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing tickets: " + e.getMessage());
        }
        if (minTicketPrice != null) {
            eventToSave.setMinPrice(minTicketPrice);
        }

        try {
            if (request.getCustomQuestions() != null) {
                eventToSave.setCustomQuestionsData(objectMapper.writeValueAsString(request.getCustomQuestions()));
            }
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing questions: " + e.getMessage());
        }

        EventApprovalStatus oldApprovalStatus = eventToSave.getEventApprovalStatus();

        if (oldApprovalStatus == EventApprovalStatus.APPROVED || oldApprovalStatus == EventApprovalStatus.UPDATE_PENDING) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus());
            if (isDraft) {
                eventToSave.setEventStatus(EventStatus.DRAFT);
                eventToSave.setEventApprovalStatus(EventApprovalStatus.DRAFT);
            } else {
                eventToSave.setEventStatus(EventStatus.APPROVED);
                eventToSave.setEventApprovalStatus(EventApprovalStatus.UPDATE_PENDING);
            }
        } else if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus());
            eventToSave.setEventStatus(isDraft ? EventStatus.DRAFT : EventStatus.PENDING);
            eventToSave.setEventApprovalStatus(isDraft ? EventApprovalStatus.DRAFT : EventApprovalStatus.PENDING);
        } else if (eventToSave.getEventStatus() == null) {
            eventToSave.setEventStatus(EventStatus.DRAFT);
            eventToSave.setEventApprovalStatus(EventApprovalStatus.DRAFT);
        }

        eventToSave.setCategory(category);
        eventToSave.setOrganizers(organizers);

        Event saved = eventRepository.save(eventToSave);

        if (flatShowtimeTickets) {
            stampShowtimeIds(saved, request);
        }
        // everything the response needs is read now: the response is built after this transaction has ended
        if (saved.getShowtimes() != null) saved.getShowtimes().size();
        if (saved.getGalleryImages() != null) saved.getGalleryImages().size();
        if (saved.getOrganizers() != null) saved.getOrganizers().size();
        return new Saved(saved, oldApprovalStatus);
    }

    /**
     * The snapshot of showtime-specific tickets is a flat list; each ticket now also carries its showtime id so that a resync
     * can send every showtime its own tickets.
     */
    private void stampShowtimeIds(Event saved, EventCreateRequest request) {
        if (saved.getShowtimes() == null) return;
        List<EventCreateRequest.TicketCreateRequest> flat = new ArrayList<>();
        for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
            if (stReq.getTickets() == null || stReq.getTickets().isEmpty()) continue;
            String showtimeId = null;
            for (EventShowtime st : saved.getShowtimes()) {
                if (st.getName() != null && st.getName().equals(stReq.getName())) {
                    showtimeId = st.getId();
                    break;
                }
            }
            for (EventCreateRequest.TicketCreateRequest t : stReq.getTickets()) {
                EventCreateRequest.TicketCreateRequest copy = TicketPayloads.copy(t);
                if (showtimeId != null) copy.setShowtimeId(showtimeId);
                flat.add(copy);
            }
        }
        try {
            if (!flat.isEmpty()) saved.setTicketsData(objectMapper.writeValueAsString(flat));
        } catch (Exception e) {
            System.out.println("DEBUG: Exception serializing tickets: " + e.getMessage());
        }
    }

    /** Short transaction: records the outcome of a ticket sync on the event. */
    @Transactional
    public void markSync(String eventId, TicketSyncStatus status, String error) {
        eventRepository.findById(eventId).ifPresent(event -> {
            event.setTicketsSyncStatus(status);
            event.setTicketsSyncError(status == TicketSyncStatus.SYNCED ? null : truncate(error));
            event.setTicketsSyncedAt(status == TicketSyncStatus.SYNCED ? LocalDateTime.now() : event.getTicketsSyncedAt());
            eventRepository.save(event);
        });
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 64 ? s : s.substring(0, 64);
    }
}
