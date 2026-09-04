package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;
    private final EventOrganizerRepository eventOrganizerRepository;
    private final SearchLogRepository searchLogRepository;

    @Override
    public EventResponse createEvent(EventCreateRequest request) {
        if (request == null) return null;

        // Create or update Organizer profile
        List<EventOrganizer> organizers = new ArrayList<>();
        if (request.getOrganizerName() != null && !request.getOrganizerName().trim().isEmpty()) {
            EventOrganizer organizer = eventOrganizerRepository
                    .findByName(request.getOrganizerName())
                    .orElseGet(() -> EventOrganizer.builder()
                            .userId("user_default_id")
                            .name(request.getOrganizerName())
                            .description(request.getOrganizerInfo())
                            .avatarUrl(request.getOrganizerLogo())
                            .build());

            organizer.setName(request.getOrganizerName());
            if (request.getOrganizerInfo() != null) organizer.setDescription(request.getOrganizerInfo());
            if (request.getOrganizerLogo() != null) organizer.setAvatarUrl(request.getOrganizerLogo());

            organizer = eventOrganizerRepository.save(organizer);
            organizers.add(organizer);
        }

        // Find category
        EventCategory category = null;
        if (request.getCategoryId() != null) {
            category = eventCategoryRepository.findById(request.getCategoryId()).orElse(null);
        }

        Event newEvent = Event.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .locationType(request.getLocationType())
                .locationName(request.getLocationName())
                .city(request.getCity())
                .ward(request.getWard())
                .address(request.getAddress())
                .thumbnailUrl(request.getThumbnailUrl())
                .bannerUrl(request.getBannerUrl())
                .startTime(request.getStartTime() != null ? request.getStartTime() : LocalDateTime.now().plusDays(30))
                .endTime(request.getEndTime() != null ? request.getEndTime() : LocalDateTime.now().plusDays(30).plusHours(4))
                .isPrivate(request.getIsPrivate())
                .confirmationMessage(request.getConfirmationMessage())
                .eventStatus(EventStatus.PENDING)
                .eventApprovalStatus(EventApprovalStatus.PENDING)
                .category(category)
                .organizers(organizers)
                .build();

        Event saved = eventRepository.save(newEvent);
        return mapToEventResponse(saved);
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
        if (query == null || query.trim().isEmpty()) return;
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
        if (event == null) return null;

        OrganizerDto organizerDto = null;
        if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
            EventOrganizer org = event.getOrganizers().get(0);
            organizerDto = OrganizerDto.builder()
                    .id(org.getId())
                    .name(org.getName() != null ? org.getName() : "Halo Entertainment")
                    .avatar(org.getAvatarUrl() != null ? org.getAvatarUrl() : "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=200&q=80")
                    .description(org.getDescription())
                    .build();
        }

        String poster = event.getBannerUrl() != null ? event.getBannerUrl() : event.getThumbnailUrl();

        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .descriptionText(event.getDescription())
                .bannerUrl(event.getBannerUrl())
                .thumbnailUrl(event.getThumbnailUrl())
                .posterImage(poster)
                .locationName(event.getLocationName())
                .address(event.getAddress())
                .author(event.getAuthor())
                .director(event.getDirector())
                .cast(event.getCast())
                .galleryImages(event.getGalleryImages())
                .datesSummary(event.getDatesSummary() != null ? event.getDatesSummary() : "20:00, 25 Tháng 09, 2026 - 23:00, 27 Tháng 09, 2026")
                .extraDaysCount(event.getExtraDaysCount() != null ? event.getExtraDaysCount() : 5)
                .minPrice(event.getMinPrice())
                .eventStatus(event.getEventStatus())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .categoryId(event.getCategory() != null ? event.getCategory().getId() : null)
                .categoryName(event.getCategory() != null ? event.getCategory().getName() : null)
                .organizer(organizerDto)
                .build();
    }

    private EventCategoryResponse mapToCategoryResponse(EventCategory category) {
        if (category == null) return null;
        return EventCategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .description(category.getDescription())
                .status(category.getStatus() != null ? category.getStatus().name() : "ACTIVE")
                .build();
    }
}
