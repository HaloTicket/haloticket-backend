package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.client.NotificationServiceClient;
import com.haloticket.event_service.client.PaymentServiceClient;
import com.haloticket.event_service.client.TicketServiceClient;
import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.request.OrganizerPayoutAccountRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.dto.response.OrganizerPayoutAccountResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.entity.SearchLog;
import com.haloticket.event_service.enums.EventApprovalStatus;
import com.haloticket.event_service.enums.EventStatus;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventOrganizerRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.SearchLogRepository;
import com.haloticket.event_service.service.EventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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

    @Autowired(required = false)
    private NotificationServiceClient notificationServiceClient;

    @Autowired(required = false)
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public EventResponse createEvent(EventCreateRequest request) {
        if (request == null)
            return null;

        // create or update Organizer profile
        List<EventOrganizer> organizers = new ArrayList<>();
        if (request.getOrganizerName() != null && !request.getOrganizerName().trim().isEmpty()) {
            String uid = (request.getUserId() != null && !request.getUserId().trim().isEmpty())
                    ? request.getUserId()
                    : "user_default_id";

            EventOrganizer organizer = eventOrganizerRepository
                    .findByName(request.getOrganizerName())
                    .orElseGet(() -> EventOrganizer.builder()
                            .userId(uid)
                            .name(request.getOrganizerName())
                            .description(request.getOrganizerInfo())
                            .avatarUrl(request.getOrganizerLogo())
                            .build());

            if (organizer.getUserId() == null || "user_default_id".equals(organizer.getUserId())) {
                organizer.setUserId(uid);
            }
            organizer.setName(request.getOrganizerName());
            if (request.getOrganizerInfo() != null)
                organizer.setDescription(request.getOrganizerInfo());
            if (request.getOrganizerLogo() != null)
                organizer.setAvatarUrl(request.getOrganizerLogo());

            organizer = eventOrganizerRepository.save(organizer);
            organizers.add(organizer);
        }

        // find category
        EventCategory category = null;
        if (request.getCategoryId() != null) {
            category = eventCategoryRepository.findById(request.getCategoryId()).orElse(null);
        }
        if (category == null && request.getCategoryName() != null) {
            category = eventCategoryRepository.findByNameContainingIgnoreCase(request.getCategoryName()).orElse(null);
        }

        Event eventToSave = null;
        if (request.getEventId() != null && !request.getEventId().trim().isEmpty()) {
            eventToSave = eventRepository.findById(request.getEventId()).orElse(null);
        }

        if (eventToSave == null) {
            eventToSave = new Event();
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
                eventToSave.setGalleryImages(new java.util.ArrayList<>(request.getGalleryImages()));
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
            if (request.getTickets() != null) {
                eventToSave.setTicketsData(objectMapper.writeValueAsString(request.getTickets()));
            }
            if (request.getCustomQuestions() != null) {
                eventToSave.setCustomQuestionsData(objectMapper.writeValueAsString(request.getCustomQuestions()));
            }
        } catch (Exception e) {
            // ignore JSON serialization exceptions
        }

        EventApprovalStatus oldApprovalStatus = eventToSave.getEventApprovalStatus();

        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            boolean isDraft = "DRAFT".equalsIgnoreCase(request.getStatus());
            eventToSave.setEventStatus(isDraft ? EventStatus.DRAFT : EventStatus.PENDING);
            eventToSave.setEventApprovalStatus(isDraft ? EventApprovalStatus.DRAFT : EventApprovalStatus.PENDING);
        } else if (eventToSave.getEventStatus() == null) {
            eventToSave.setEventStatus(EventStatus.DRAFT);
            eventToSave.setEventApprovalStatus(EventApprovalStatus.DRAFT);
        }

        eventToSave.setCategory(category);
        if (!organizers.isEmpty()) {
            eventToSave.setOrganizers(organizers);
        }

        Event saved = eventRepository.save(eventToSave);
        syncTicketsToTicketService(saved.getId(), request.getTickets());
        String uid = (request.getUserId() != null && !request.getUserId().trim().isEmpty()) ? request.getUserId()
                : "user_default_id";
        syncPayoutAccountToPaymentService(uid, request);

        boolean isNewlySubmitted = (oldApprovalStatus == null || oldApprovalStatus == EventApprovalStatus.DRAFT)
                && saved.getEventApprovalStatus() == EventApprovalStatus.PENDING;

        if (isNewlySubmitted) {
            publishNotification("ADMIN", "NEW_EVENT_APPROVAL", "Sự kiện mới cần duyệt",
                    "Sự kiện \"" + saved.getTitle() + "\" vừa được gửi yêu cầu phê duyệt.");
        }

        return mapToEventResponse(saved);

    }

    private void syncTicketsToTicketService(String eventId, List<EventCreateRequest.TicketCreateRequest> tickets) {
        if (eventId == null || tickets == null || tickets.isEmpty())
            return;
        try {
            ticketServiceClient.syncTicketTypesForEvent(eventId, tickets);
        } catch (Exception e) {
            System.err.println("Warning: Could not sync tickets to ticket-service: " + e.getMessage());
        }
    }

    private void syncPayoutAccountToPaymentService(String userId, EventCreateRequest request) {
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

            paymentServiceClient.saveOrUpdatePayoutAccount(payoutReq);
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
            return paymentServiceClient.getPayoutAccountByUserId(userId);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public OrganizerDto getOrganizerProfile(String userId) {
        String uid = (userId != null && !userId.isEmpty()) ? userId : "user_default_id";
        return eventOrganizerRepository.findFirstByUserIdOrderByCreatedAtDesc(uid)
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

        // fetch organizer payout / banking details dynamically from payment-service
        String orgUserId = organizerDto != null ? organizerDto.getUserId() : null;
        OrganizerPayoutAccountResponse payoutData = fetchPayoutAccountFromPaymentService(orgUserId);

        String accountHolder = payoutData != null ? payoutData.getAccountHolder() : null;
        String accountNumber = payoutData != null ? payoutData.getAccountNumber() : null;
        String bankName = payoutData != null ? payoutData.getBankName() : null;
        String bankBranch = payoutData != null ? payoutData.getBankBranch() : null;
        String businessType = payoutData != null ? payoutData.getBusinessType() : null;
        String taxName = payoutData != null ? payoutData.getTaxName() : null;
        String taxAddress = payoutData != null ? payoutData.getTaxAddress() : null;
        String taxCode = payoutData != null ? payoutData.getTaxCode() : null;

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
                .tickets(ticketsObj)
                .customQuestions(customQuestionsObj)
                .accountHolder(accountHolder)
                .accountNumber(accountNumber)
                .bankName(bankName)
                .bankBranch(bankBranch)
                .businessType(businessType)
                .taxName(taxName)
                .taxAddress(taxAddress)
                .taxCode(taxCode)
                .organizer(organizerDto)
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

        event.setEventApprovalStatus(EventApprovalStatus.APPROVED);
        event.setEventStatus(EventStatus.APPROVED);
        Event saved = eventRepository.save(event);

        String recipientUserId = (saved.getOrganizers() != null && !saved.getOrganizers().isEmpty()
                && saved.getOrganizers().get(0).getUserId() != null)
                        ? saved.getOrganizers().get(0).getUserId()
                        : "user_default_id";

        publishNotification(recipientUserId, "EVENT_APPROVED", "Sự kiện đã được phê duyệt!",
                "Sự kiện \"" + saved.getTitle() + "\" của bạn đã được phê duyệt chính thức.");

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

        publishNotification(recipientUserId, "EVENT_REJECTED", "Sự kiện bị từ chối phê duyệt",
                "Sự kiện \"" + saved.getTitle() + "\" của bạn bị từ chối. Lý do: "
                        + (reason != null ? reason : "Cần bổ sung thông tin."));

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
