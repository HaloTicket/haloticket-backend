package com.haloticket.event_service.service;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;

import java.util.List;

public interface VenueService {
    List<VenueResponse> getAllVenues();
    VenueResponse getVenueById(Long id);
    VenueResponse createVenue(VenueRequest request);
    VenueResponse updateVenue(Long id, VenueRequest request);
    VenueResponse toggleVerification(Long id);
    void deleteVenue(Long id);
}
