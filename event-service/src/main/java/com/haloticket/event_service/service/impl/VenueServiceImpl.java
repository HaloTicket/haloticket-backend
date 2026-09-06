package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;
import com.haloticket.event_service.entity.Event;
import com.haloticket.event_service.entity.Venue;
import com.haloticket.event_service.repository.EventRepository;
import com.haloticket.event_service.repository.VenueRepository;
import com.haloticket.event_service.service.VenueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenueServiceImpl implements VenueService {

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;

    @Override
    @Transactional
    public List<VenueResponse> getAllVenues() {
        List<Venue> venues = venueRepository.findAll();

        // If DB table is empty, auto-sync and seed initial venues from existing DB events
        if (venues.isEmpty()) {
            syncVenuesFromEvents();
            venues = venueRepository.findAll();
        }

        return venues.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public VenueResponse getVenueById(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));
        return mapToResponse(venue);
    }

    @Override
    @Transactional
    public VenueResponse createVenue(VenueRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên địa điểm không được để trống");
        }

        Optional<Venue> existing = venueRepository.findByNameIgnoreCase(request.getName().trim());
        if (existing.isPresent()) {
            // Update existing venue if already created
            Venue venue = existing.get();
            if (request.getAddress() != null) venue.setAddress(request.getAddress().trim());
            if (request.getCity() != null) venue.setCity(request.getCity().trim());
            if (request.getWard() != null) venue.setWard(request.getWard().trim());
            if (request.getCapacity() != null) venue.setCapacity(request.getCapacity());
            if (request.getType() != null) venue.setType(request.getType());
            if (request.getVerified() != null) venue.setVerified(request.getVerified());
            return mapToResponse(venueRepository.save(venue));
        }

        Venue newVenue = Venue.builder()
                .name(request.getName().trim())
                .address(request.getAddress() != null ? request.getAddress().trim() : request.getName().trim())
                .city(request.getCity() != null ? request.getCity().trim() : "TP. Hồ Chí Minh")
                .ward(request.getWard() != null ? request.getWard().trim() : "")
                .capacity(request.getCapacity() != null ? request.getCapacity() : 1000)
                .type(request.getType() != null ? request.getType() : "indoor")
                .verified(request.getVerified() != null ? request.getVerified() : true)
                .eventsHosted(0)
                .build();

        Venue saved = venueRepository.save(newVenue);
        log.info("Created new official Venue in DB: {} (ID: {})", saved.getName(), saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public VenueResponse updateVenue(Long id, VenueRequest request) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            venue.setName(request.getName().trim());
        }
        if (request.getAddress() != null) venue.setAddress(request.getAddress().trim());
        if (request.getCity() != null) venue.setCity(request.getCity().trim());
        if (request.getWard() != null) venue.setWard(request.getWard().trim());
        if (request.getCapacity() != null) venue.setCapacity(request.getCapacity());
        if (request.getType() != null) venue.setType(request.getType());
        if (request.getVerified() != null) venue.setVerified(request.getVerified());

        Venue updated = venueRepository.save(venue);
        log.info("Updated Venue ID {}: {}", updated.getId(), updated.getName());
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public VenueResponse toggleVerification(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));

        boolean newVerified = !Boolean.TRUE.equals(venue.getVerified());
        venue.setVerified(newVerified);
        Venue updated = venueRepository.save(venue);
        log.info("Toggled verification for Venue ID {}: {}", updated.getId(), updated.getVerified());
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deleteVenue(Long id) {
        if (!venueRepository.existsById(id)) {
            throw new RuntimeException("Không tìm thấy địa điểm với ID: " + id);
        }
        venueRepository.deleteById(id);
        log.info("Deleted Venue ID: {}", id);
    }

    private void syncVenuesFromEvents() {
        try {
            List<Event> events = eventRepository.findAll();
            Map<String, Venue> initialVenues = new LinkedHashMap<>();

            // Preset default venues baseline if empty DB
            addPresetVenue(initialVenues, "Sân vận động Quốc gia Mỹ Đình", "Lê Đức Thọ, Mỹ Đình, Nam Từ Liêm", "Hà Nội", 40000, "outdoor", true);
            addPresetVenue(initialVenues, "Sân vận động Quân khu 7", "202 Hoàng Văn Thụ, Phường 9, Phú Nhuận", "TP. Hồ Chí Minh", 25000, "outdoor", true);
            addPresetVenue(initialVenues, "Trung tâm Hội nghị GEM Center", "8 Nguyễn Bỉnh Khiêm, Đa Kao, Quận 1", "TP. Hồ Chí Minh", 3000, "indoor", true);
            addPresetVenue(initialVenues, "Nhà thi đấu Phú Thọ", "221 Lý Thường Kiệt, Phường 15, Quận 11", "TP. Hồ Chí Minh", 5000, "indoor", true);

            for (Event event : events) {
                if (event.getLocationName() != null && !event.getLocationName().trim().isEmpty()) {
                    String key = event.getLocationName().toLowerCase().trim();
                    Venue venue = initialVenues.get(key);
                    if (venue != null) {
                        venue.setEventsHosted(venue.getEventsHosted() + 1);
                    } else {
                        initialVenues.put(key, Venue.builder()
                                .name(event.getLocationName().trim())
                                .address(event.getLocationName().trim())
                                .city("TP. Hồ Chí Minh")
                                .ward("")
                                .capacity(3000)
                                .type("indoor")
                                .verified(true)
                                .eventsHosted(1)
                                .build());
                    }
                }
            }

            venueRepository.saveAll(initialVenues.values());
            log.info("Initialized {} venues in DB", initialVenues.size());
        } catch (Exception e) {
            log.error("Failed to sync venues from events", e);
        }
    }

    private void addPresetVenue(Map<String, Venue> map, String name, String address, String city, int capacity, String type, boolean verified) {
        String key = name.toLowerCase().trim();
        map.put(key, Venue.builder()
                .name(name)
                .address(address)
                .city(city)
                .capacity(capacity)
                .type(type)
                .verified(verified)
                .eventsHosted(0)
                .build());
    }

    private VenueResponse mapToResponse(Venue venue) {
        return VenueResponse.builder()
                .id(venue.getId())
                .name(venue.getName())
                .address(venue.getAddress())
                .city(venue.getCity())
                .ward(venue.getWard())
                .capacity(venue.getCapacity())
                .type(venue.getType())
                .verified(venue.getVerified())
                .eventsHosted(venue.getEventsHosted())
                .createdAt(venue.getCreatedAt())
                .updatedAt(venue.getUpdatedAt())
                .build();
    }
}
