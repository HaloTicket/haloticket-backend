package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.EventCategory;
import com.haloticket.event_service.entity.EventOrganizer;
import com.haloticket.event_service.repository.EventCategoryRepository;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final EventCategoryRepository eventCategoryRepository;

    @Override
    public EventResponse createEvent(EventCreateRequest request) {
        return null;
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
