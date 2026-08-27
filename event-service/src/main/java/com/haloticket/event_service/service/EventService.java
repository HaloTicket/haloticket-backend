package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.EventCreateRequest;
import com.haloticket.event_service.dto.response.EventResponse;

public interface EventService {
    EventResponse createEvent(EventCreateRequest request);
}
