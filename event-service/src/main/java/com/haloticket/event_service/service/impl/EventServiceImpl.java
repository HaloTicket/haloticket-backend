package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.client.NotificationServiceClient;
import com.haloticket.event_service.client.PaymentServiceClient;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.client.UserServiceClient;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.dto.response.EventMemberResponse;
import com.haloticket.event_service.dto.response.EventShowtimeDto;
import com.haloticket.event_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.event_service.dto.response.OrganizerEligibilityResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.EventShowtime;
import com.haloticket.event_service.entity.SearchLog;
import com.haloticket.event_service.entity.StaffAssignment;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.enums.StaffRole;
import com.haloticket.event_service.enums.StaffAssignmentStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.SearchLogRepository;
import com.haloticket.event_service.repository.StaffAssignmentRepository;
import com.haloticket.event_service.service.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final EventOrganizerRepository eventOrganizerRepository;
    private final SearchLogRepository searchLogRepository;
    private final TicketServiceClient ticketServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final UserServiceClient userServiceClient;
    private final StaffAssignmentRepository staffAssignmentRepository;

    @Autowired(required = false)
    private NotificationServiceClient notificationServiceClient;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public EventResponse createEvent(EventCreateRequest request, String authorization) {
        if (request == null)
            return null;

        OrganizerEligibilityResponse eligibility = requireEligibleOrganizer(authorization);
        String uid = eligibility.userId();

        Event eventToSave;
        if (request.getEventId() != null && !request.getEventId().isBlank()) {
            eventToSave = eventRepository.findById(request.getEventId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));
            if (!isOwnedBy(eventToSave, uid)) {
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

        // auto create OWNER StaffAssignment
        if (eventToSave.getId() == null) {
            StaffAssignment staff = StaffAssignment.builder()
                    .event(eventToSave)
                    .userId(uid)
                    .name(request.getOrganizerName())
                    .role(StaffRole.OWNER)
                    .status(StaffAssignmentStatus.ACTIVE)
                    .build();
            if (eventToSave.getStaffAssignments() == null) {
                eventToSave.setStaffAssignments(new java.util.ArrayList<>());
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

        try {
            if (request.getShowtimes() != null && !request.getShowtimes().isEmpty()) {
                if (eventToSave.getShowtimes() == null) {
                    eventToSave.setShowtimes(new ArrayList<>());
                } else {
                    eventToSave.getShowtimes().clear();
                }
                for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
                    EventShowtime st = EventShowtime.builder()
                            .event(eventToSave)
                            .name(stReq.getName())
                            .startTime(stReq.getStartTime() != null ? stReq.getStartTime() : startEvt)
                            .endTime(stReq.getEndTime() != null ? stReq.getEndTime() : endEvt)
                            .saleStartTime(stReq.getSaleStartTime())
                            .saleEndTime(stReq.getSaleEndTime())
                            .build();
                    eventToSave.getShowtimes().add(st);
                }
                // convert showtimes to JSON for backward compatibility / ticket syncing
                eventToSave.setTicketsData(objectMapper.writeValueAsString(request.getShowtimes()));
            } else if (request.getTickets() != null) {
                eventToSave.setTicketsData(objectMapper.writeValueAsString(request.getTickets()));
            }
            if (request.getCustomQuestions() != null) {
                eventToSave.setCustomQuestionsData(objectMapper.writeValueAsString(request.getCustomQuestions()));
            }
        } catch (Exception e) {
            // ignore JSON serialization exceptions
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
        if (saved.getShowtimes() != null && !saved.getShowtimes().isEmpty() && request.getShowtimes() != null) {
            for (EventShowtime st : saved.getShowtimes()) {
                List<EventCreateRequest.TicketCreateRequest> stTickets = null;
                for (EventCreateRequest.EventShowtimeRequest stReq : request.getShowtimes()) {
                    if (stReq.getName().equals(st.getName())) {
                        stTickets = stReq.getTickets();
                        break;
                    }
                }
                if (stTickets == null || stTickets.isEmpty()) {
                    stTickets = request.getTickets();
                }
                syncTicketsToTicketService(saved.getId(), st.getId(), stTickets);
            }
        } else {
            syncTicketsToTicketService(saved.getId(), null, request.getTickets());
        }
        syncPayoutAccountToPaymentService(uid, request, authorization);

        boolean isNewlySubmitted = (oldApprovalStatus == null || oldApprovalStatus == EventApprovalStatus.DRAFT)
                && saved.getEventApprovalStatus() == EventApprovalStatus.PENDING;
        boolean isUpdatedApproval = (oldApprovalStatus == EventApprovalStatus.APPROVED || oldApprovalStatus == EventApprovalStatus.UPDATE_PENDING)
                && saved.getEventApprovalStatus() == EventApprovalStatus.UPDATE_PENDING;

        if (isNewlySubmitted) {
            publishNotification("ADMIN", "NEW_EVENT_APPROVAL", "Sự kiện mới cần duyệt",
                    "Sự kiện \"" + saved.getTitle() + "\" vừa được gửi yêu cầu phê duyệt.");
        } else if (isUpdatedApproval) {
            publishNotification("ADMIN", "EVENT_UPDATED", "Sự kiện cập nhật thông tin",
                    "Sự kiện \"" + saved.getTitle() + "\" đã được Ban tổ chức cập nhật thông tin và cần xem xét.");
        }

        return mapToEventResponse(saved);
    }

    @Override
    public EventResponse updateEvent(String id, EventCreateRequest request, String authorization) {
        if (request == null) {
            request = new EventCreateRequest();
        }
        request.setEventId(id);
        return createEvent(request, authorization);
    }

    private OrganizerEligibilityResponse requireEligibleOrganizer(String authorization) {
        try {
            OrganizerEligibilityResponse eligibility = userServiceClient.getOrganizerEligibility(authorization);
            if (eligibility == null || !eligibility.eligible() || eligibility.userId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EKYC_REQUIRED");
            }
            return eligibility;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "EKYC_STATUS_UNAVAILABLE");
        }
    }

    private boolean isOwnedBy(Event event, String userId) {
        return event.getOrganizers() != null && event.getOrganizers().stream()
                .anyMatch(organizer -> userId.equals(organizer.getUserId()));
    }

    private void syncTicketsToTicketService(String eventId, String showtimeId, List<EventCreateRequest.TicketCreateRequest> tickets) {
        if (eventId == null || tickets == null || tickets.isEmpty())
            return;
        try {
            ticketServiceClient.syncTicketTypesForEvent(eventId, showtimeId, tickets);
        } catch (Exception e) {
            System.err.println("Warning: Could not sync tickets to ticket-service: " + e.getMessage());
        }
    }

    private void syncPayoutAccountToPaymentService(String userId, EventCreateRequest request, String authorization) {
        if (userId == null || request.getAccountHolder() == null || request.getAccountHolder().trim().isEmpty())
            return;
        try {
            OrganizerPayoutAccountRequest payoutReq = OrganizerPayoutAccountRequest.builder()
                    .organizerUserId(userId)
                    .accountHolder(request.getAccountHolder())
                    .accountNumber(request.getAccountNumber())
                    .bankName(request.getBankName())
                    .bankBranch(request.getBankBranch())
                    .businessType(request.getBusinessType())
                    .taxName(request.getTaxName())
                    .taxCode(request.getTaxCode())
                    .taxAddress(request.getTaxAddress())
                    .build();

            paymentServiceClient.saveOrUpdatePayoutAccount(authorization, payoutReq);
        } catch (Exception e) {
            System.err.println("Warning: Could not sync payout account to payment-service: " + e.getMessage());
        }
    }

    private Object fetchTicketsFromTicketService(String eventId) {
        if (eventId == null)
            return null;
        try {
            return ticketServiceClient.getTicketTypesByEventId(eventId);
        } catch (Exception e) {
            return null;
        }
    }

    private OrganizerPayoutAccountResponse fetchPayoutAccountFromPaymentService(String userId) {
        if (userId == null)
            return null;
        try {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public OrganizerDto getOrganizerProfile(String authorization) {
        String userId = requireEligibleOrganizer(authorization).userId();
        return eventOrganizerRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(org -> OrganizerDto.builder()
                        .id(org.getId())
                        .name(org.getName())
                        .avatar(org.getAvatarUrl())
                        .description(org.getDescription())
                        .build())
                .orElse(null);
    }

    @Override
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(this::mapToEventResponse)
                .collect(Collectors.toList());
    }

    @Override
    public EventResponse getEventById(String id) {
        return eventRepository.findById(id)
                .map(this::mapToEventResponse)
                .orElseGet(() -> eventRepository.findAll().stream().findFirst()
                        .map(this::mapToEventResponse)
                        .orElse(null));
    }

    @Override
    public List<EventCategoryResponse> getAllCategories() {
        return eventCategoryRepository.findAll().stream()
                .map(this::mapToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getTopSearchedKeywords(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<String> topKeywords = searchLogRepository.findTopSearchedKeywords(pageable);
        if (topKeywords == null || topKeywords.isEmpty()) {
            return List.of("idecaf", "xoay tròn", "Pickleball World Cup");
        }
        return topKeywords;
    }

    @Override
    public void logSearchQuery(String query) {
        if (query == null || query.trim().isEmpty())
            return;
        String cleanQuery = query.trim().toLowerCase();
        SearchLog searchLog = searchLogRepository.findByQueryTermIgnoreCase(cleanQuery)
                .orElseGet(() -> SearchLog.builder()
                        .queryTerm(cleanQuery)
                        .searchCount(0L)
                        .build());
        searchLog.setSearchCount(searchLog.getSearchCount() + 1);
        searchLogRepository.save(searchLog);
    }

    private EventResponse mapToEventResponse(Event event) {
        if (event == null)
            return null;

        OrganizerDto organizerDto = null;
        if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
            EventOrganizer org = event.getOrganizers().get(0);
            organizerDto = OrganizerDto.builder()
                    .id(org.getId())
                    .userId(org.getUserId())
                    .name(org.getName() != null ? org.getName() : "Halo Entertainment")
                    .avatar(org.getAvatarUrl() != null ? org.getAvatarUrl()
                            : "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&q=80")
                    .description(org.getDescription())
                    .build();
        }

        String poster = event.getBannerUrl() != null ? event.getBannerUrl() : event.getThumbnailUrl();

        // fetch tickets dynamically from ticket-service, fallback to local JSON
        Object ticketsObj = fetchTicketsFromTicketService(event.getId());
        if ((ticketsObj == null || (ticketsObj instanceof List && ((List<?>) ticketsObj).isEmpty()))
                && event.getTicketsData() != null && !event.getTicketsData().trim().isEmpty()) {
            try {
                ticketsObj = objectMapper.readValue(event.getTicketsData(), Object.class);
            } catch (Exception e) {
                ticketsObj = event.getTicketsData();
            }
        }

        Object customQuestionsObj = null;
        if (event.getCustomQuestionsData() != null && !event.getCustomQuestionsData().trim().isEmpty()) {
            try {
                customQuestionsObj = objectMapper.readValue(event.getCustomQuestionsData(), Object.class);
            } catch (Exception e) {
                customQuestionsObj = event.getCustomQuestionsData();
            }
        }

        List<EventShowtimeDto> showtimesDto = null;
        if (event.getShowtimes() != null && !event.getShowtimes().isEmpty()) {
            showtimesDto = event.getShowtimes().stream()
                    .map(st -> EventShowtimeDto.builder()
                            .id(st.getId())
                            .name(st.getName())
                            .startTime(st.getStartTime())
                            .endTime(st.getEndTime())
                            .saleStartTime(st.getSaleStartTime())
                            .saleEndTime(st.getSaleEndTime())
                            .build())
                    .collect(Collectors.toList());
        }

        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .descriptionText(event.getDescription())
                .bannerUrl(event.getBannerUrl())
                .thumbnailUrl(event.getThumbnailUrl())
                .posterImage(poster)
                .locationType(event.getLocationType())
                .locationName(event.getLocationName())
                .city(event.getCity())
                .ward(event.getWard())
                .address(event.getAddress())
                .galleryImages(event.getGalleryImages())
                .datesSummary(event.getDatesSummary() != null ? event.getDatesSummary()
                        : "20:00, 25 Tháng 09, 2026 - 23:00, 27 Tháng 09, 2026")
                .extraDaysCount(event.getExtraDaysCount() != null ? event.getExtraDaysCount() : 5)
                .minPrice(event.getMinPrice())
                .eventStatus(event.getEventStatus())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .categoryId(event.getCategory() != null ? event.getCategory().getId() : null)
                .categoryName(event.getCategory() != null ? event.getCategory().getName() : null)
                .eventApprovalStatus(
                        event.getEventApprovalStatus() != null ? event.getEventApprovalStatus().name() : "PENDING")
                .rejectionReason(event.getRejectionReason())
                .rejectionNotes(event.getRejectionNotes())
                .submittedAt(event.getCreatedAt() != null ? event.getCreatedAt().toString() : "Mới gửi")
                .isPrivate(event.getIsPrivate())
                .confirmationMessage(event.getConfirmationMessage())
                .enableCustomQuestions(event.getEnableCustomQuestions())
                .questionScope(event.getQuestionScope())
                .showtimes(showtimesDto)
                .tickets(ticketsObj)
                .customQuestions(customQuestionsObj)
                .organizer(organizerDto)
                .staffAssignments(staffAssignmentRepository.findByEventId(event.getId()).stream()
                        .map(a -> EventMemberResponse.builder()
                                .id(a.getId())
                                .name(a.getName())
                                .email(a.getEmail())
                                .role(a.getRole())
                                .status(a.getStatus().name())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    @Override
    public List<EventResponse> getApprovalEvents(String status) {
        List<Event> events = eventRepository.findAll();
        if (status != null && !"ALL".equalsIgnoreCase(status.trim())) {
            events = events.stream()
                    .filter(e -> e.getEventApprovalStatus() != null
                            && e.getEventApprovalStatus().name().equalsIgnoreCase(status.trim()))
                    .collect(Collectors.toList());
        }
        return events.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                        return b.getCreatedAt().compareTo(a.getCreatedAt());
                    }
                    return b.getId().compareTo(a.getId());
                })
                .map(this::mapToEventResponse)
                .collect(Collectors.toList());
    }

    @Override
    public EventResponse approveEvent(String id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        EventApprovalStatus prevStatus = event.getEventApprovalStatus();
        event.setEventApprovalStatus(EventApprovalStatus.APPROVED);
        event.setEventStatus(EventStatus.APPROVED);
        Event saved = eventRepository.save(event);

        String recipientUserId = (saved.getOrganizers() != null && !saved.getOrganizers().isEmpty()
                && saved.getOrganizers().get(0).getUserId() != null)
                        ? saved.getOrganizers().get(0).getUserId()
                        : "user_default_id";

        if (prevStatus == EventApprovalStatus.UPDATE_PENDING) {
            publishNotification(recipientUserId, "EVENT_UPDATE_APPROVED", "Sự kiện đã được phê duyệt cập nhật!",
                    "Các thay đổi cập nhật cho sự kiện \"" + saved.getTitle() + "\" đã được phê duyệt.");
        } else {
            publishNotification(recipientUserId, "EVENT_APPROVED", "Sự kiện đã được phê duyệt!",
                    "Sự kiện \"" + saved.getTitle() + "\" của bạn đã được phê duyệt chính thức.");
        }

        return mapToEventResponse(saved);
    }

    @Override
    public EventResponse rejectEvent(String id, String reason, String notes) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy sự kiện với ID: " + id));

        event.setEventApprovalStatus(EventApprovalStatus.REJECTED);
        event.setRejectionReason(reason);
        event.setRejectionNotes(notes);
        Event saved = eventRepository.save(event);

        String recipientUserId = (saved.getOrganizers() != null && !saved.getOrganizers().isEmpty()
                && saved.getOrganizers().get(0).getUserId() != null)
                        ? saved.getOrganizers().get(0).getUserId()
                        : "user_default_id";

        publishNotification(recipientUserId, "EVENT_REJECTED", "Sự kiện đã bị từ chối, xem lý do.",
                "Sự kiện \"" + saved.getTitle() + "\" của bạn bị từ chối phê duyệt. Lý do: "
                        + (reason != null ? reason : "Cần chỉnh sửa lại thông tin.") + ". Nhấn vào để xem và khắc phục.");

        return mapToEventResponse(saved);
    }

    private void publishNotification(String userId, String type, String title, String message) {
        Map<String, Object> payload = Map.of(
                "userId", userId,
                "type", type,
                "title", title,
                "message", message,
                "timestamp", LocalDateTime.now().toString());

        if (kafkaTemplate != null) {
            try {
                kafkaTemplate.send("haloticket.notifications.v1", userId, payload);
            } catch (Exception e) {
                // silent catch
            }
        }

        if (notificationServiceClient != null) {
            try {
                notificationServiceClient.publishNotification(payload);
            } catch (Exception e) {
                // silent catch
            }
        }
    }

    private EventCategoryResponse mapToCategoryResponse(EventCategory category) {
        if (category == null)
            return null;
        return EventCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .status(category.getStatus() != null ? category.getStatus().name() : "ACTIVE")
                .build();
    }
}
