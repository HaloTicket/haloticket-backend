package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventCategoryResponse;
import com.haloticket.event_service.dto.response.EventResponse;
import com.haloticket.event_service.dto.response.OrganizerDto;

import java.util.List;

public interface EventService {
    EventResponse createEvent(EventCreateRequest request, String authorization);
    List<EventResponse> getAllEvents();
    EventResponse getEventById(String id);
    List<EventCategoryResponse> getAllCategories();
    List<String> getTopSearchedKeywords(int limit);
    void logSearchQuery(String query);
    OrganizerDto getOrganizerProfile(String authorization);
    List<EventResponse> getApprovalEvents(String status);
    EventResponse approveEvent(String id);
    EventResponse rejectEvent(String id, String reason, String notes);
}
