package com.haloticket.event_service.service.impl;

import com.haloticket.event_service.dto.request.VenueRequest;
import com.haloticket.event_service.dto.response.VenueResponse;
import com.haloticket.event_service.dto.response.VenueSeatmapsResponse;
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

/** Cài đặt nghiệp vụ địa điểm tổ chức: CRUD, xác minh và mẫu sơ đồ chỗ ngồi; ghép sơ đồ do nhà tổ chức dựng từ ticket-service. */
@Service
@RequiredArgsConstructor
@Slf4j
public class VenueServiceImpl implements VenueService {

    private final VenueRepository venueRepository;
    private final EventRepository eventRepository;
    private final com.haloticket.event_service.client.TicketServiceClient ticketServiceClient;

    /** Lấy toàn bộ địa điểm. Nếu bảng đang trống thì nạp một số địa điểm mặc định và các địa điểm suy ra từ sự kiện, rồi đọc lại. */
    @Override
    @Transactional
    public List<VenueResponse> getAllVenues() {
        List<Venue> venues = venueRepository.findAll();

        // nếu bảng trống thì tự đồng bộ và nạp địa điểm ban đầu từ các sự kiện có sẵn
        if (venues.isEmpty()) {
            syncVenuesFromEvents();
            venues = venueRepository.findAll();
        }

        return venues.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lấy địa điểm theo id.
     * @throws RuntimeException khi không tìm thấy
     */
    @Override
    public VenueResponse getVenueById(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));
        return mapToResponse(venue);
    }

    /**
     * Tạo địa điểm mới. Nếu đã có địa điểm cùng tên (không phân biệt hoa thường) thì cập nhật các trường được gửi lên của địa điểm đó thay vì tạo bản trùng. Trường thiếu dùng mặc định (TP. Hồ Chí Minh, sức chứa 1000, loại {@code indoor}, công khai).
     * @param request thông tin địa điểm
     * @return địa điểm đã tạo hoặc cập nhật
     * @throws IllegalArgumentException khi tên trống
     */
    @Override
    @Transactional
    public VenueResponse createVenue(VenueRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên địa điểm không được để trống");
        }

        Optional<Venue> existing = venueRepository.findByNameIgnoreCase(request.getName().trim());
        if (existing.isPresent()) {
            // đã có địa điểm cùng tên thì cập nhật địa điểm đó
            Venue venue = existing.get();
            if (request.getAddress() != null) venue.setAddress(request.getAddress().trim());
            if (request.getCity() != null) venue.setCity(request.getCity().trim());
            if (request.getWard() != null) venue.setWard(request.getWard().trim());
            if (request.getCapacity() != null) venue.setCapacity(request.getCapacity());
            if (request.getType() != null) venue.setType(request.getType());
            if (request.getVerified() != null) venue.setVerified(request.getVerified());
            if (request.getIsPublic() != null) venue.setIsPublic(request.getIsPublic());
            if (request.getOrganizerId() != null) venue.setOrganizerId(request.getOrganizerId());
            if (request.getSeatmapTemplate() != null) venue.setSeatmapTemplate(request.getSeatmapTemplate());
            return mapToResponse(venueRepository.save(venue));
        }

        Venue newVenue = Venue.builder()
                .name(request.getName().trim())
                .address(request.getAddress() != null ? request.getAddress().trim() : request.getName().trim())
                .city(request.getCity() != null ? request.getCity().trim() : "TP. Hồ Chí Minh")
                .ward(request.getWard() != null ? request.getWard().trim() : "")
                .capacity(request.getCapacity() != null ? request.getCapacity() : 1000)
                .type(request.getType() != null ? request.getType() : "indoor")
                .verified(request.getVerified() != null ? request.getVerified() : (Boolean.FALSE.equals(request.getIsPublic()) ? false : true))
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : true)
                .organizerId(request.getOrganizerId())
                .seatmapTemplate(request.getSeatmapTemplate())
                .eventsHosted(0)
                .build();

        Venue saved = venueRepository.save(newVenue);
        log.info("Created new Venue in DB: {} (ID: {}) isPublic: {}", saved.getName(), saved.getId(), saved.getIsPublic());
        return mapToResponse(saved);
    }

    /**
     * Cập nhật các trường được gửi lên của địa điểm, trường null giữ nguyên.
     * @throws RuntimeException khi không tìm thấy
     */
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
        if (request.getIsPublic() != null) venue.setIsPublic(request.getIsPublic());
        if (request.getOrganizerId() != null) venue.setOrganizerId(request.getOrganizerId());
        if (request.getSeatmapTemplate() != null) venue.setSeatmapTemplate(request.getSeatmapTemplate());

        Venue updated = venueRepository.save(venue);
        log.info("Updated Venue ID {}: {}", updated.getId(), updated.getName());
        return mapToResponse(updated);
    }

    /**
     * Đảo trạng thái đã xác minh của địa điểm.
     * @throws RuntimeException khi không tìm thấy
     */
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

    /**
     * Xóa địa điểm theo id.
     * @throws RuntimeException khi không tìm thấy
     */
    @Override
    @Transactional
    public void deleteVenue(Long id) {
        if (!venueRepository.existsById(id)) {
            throw new RuntimeException("Không tìm thấy địa điểm với ID: " + id);
        }
        venueRepository.deleteById(id);
        log.info("Deleted Venue ID: {}", id);
    }

    /** Khởi tạo bảng địa điểm: nạp vài địa điểm mặc định rồi thêm địa điểm theo tên địa điểm của từng sự kiện, đếm số sự kiện đã tổ chức. Lỗi chỉ ghi log. */
    private void syncVenuesFromEvents() {
        try {
            List<Event> events = eventRepository.findAll();
            Map<String, Venue> initialVenues = new LinkedHashMap<>();

            // nạp sẵn các địa điểm mặc định khi cơ sở dữ liệu còn trống
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

    /** Thêm một địa điểm mặc định vào bản đồ khởi tạo, khóa là tên viết thường. */
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

    /**
     * Lấy mẫu sơ đồ của admin cùng các sơ đồ do nhà tổ chức dựng. Sự kiện được coi là diễn ra tại địa điểm khi tên địa điểm của sự kiện và tên địa điểm này chứa nhau; chỉ lấy sự kiện có bản nháp sơ đồ trên ticket-service. Sức chứa và số khu vực hiện là giá trị ước lượng cố định.
     * @throws RuntimeException khi không tìm thấy địa điểm
     */
    @Override
    @Transactional(readOnly = true)
    public VenueSeatmapsResponse getVenueSeatmaps(Long id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));

        // tìm các sự kiện gắn với địa điểm này theo tên hoặc địa chỉ
        List<Event> allEvents = eventRepository.findAll();
        List<VenueSeatmapsResponse.OrganizerSeatmapDto> seatmaps = new ArrayList<>();

        String venueNameLower = venue.getName() != null ? venue.getName().trim().toLowerCase() : "";
        for (Event event : allEvents) {
            String eventLoc = event.getLocationName() != null ? event.getLocationName().trim().toLowerCase() : "";
            if (!venueNameLower.isEmpty() && (eventLoc.contains(venueNameLower) || venueNameLower.contains(eventLoc))) {
                String organizerName = "Ban Tổ Chức";
                if (event.getOrganizers() != null && !event.getOrganizers().isEmpty()) {
                    organizerName = event.getOrganizers().get(0).getName();
                }

                // kiểm tra sơ đồ có sẵn từ ticket-service hay cục bộ
                String draftJson = null;
                try {
                    if (ticketServiceClient != null) {
                        Object layoutObj = ticketServiceClient.getSeatLayoutByEventId(event.getId());
                        if (layoutObj instanceof Map) {
                            Map<?, ?> map = (Map<?, ?>) layoutObj;
                            draftJson = (String) map.get("draftJson");
                        }
                    }
                } catch (Exception e) {
                    log.debug("Ticket service layout not found for event {}: {}", event.getId(), e.getMessage());
                }

                if (draftJson != null && !draftJson.isBlank()) {
                    seatmaps.add(VenueSeatmapsResponse.OrganizerSeatmapDto.builder()
                            .eventId(event.getId())
                            .eventTitle(event.getTitle())
                            .organizerName(organizerName)
                            .updatedAt(event.getUpdatedAt() != null ? event.getUpdatedAt() : event.getCreatedAt())
                            .draftJson(draftJson)
                            .capacity(event.getShowtimes() != null && !event.getShowtimes().isEmpty() ? 2000 : 1000)
                            .zonesCount(3)
                            .build());
                }
            }
        }

        return VenueSeatmapsResponse.builder()
                .venueId(venue.getId())
                .venueName(venue.getName())
                .adminTemplate(venue.getSeatmapTemplate())
                .organizerSeatmaps(seatmaps)
                .build();
    }

    /**
     * Lưu mẫu sơ đồ chỗ ngồi (JSON) mà admin đặt cho địa điểm.
     * @throws RuntimeException khi không tìm thấy
     */
    @Override
    @Transactional
    public VenueResponse saveVenueSeatmapTemplate(Long id, String seatmapTemplate) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy địa điểm với ID: " + id));

        venue.setSeatmapTemplate(seatmapTemplate);
        Venue updated = venueRepository.save(venue);
        log.info("Saved Admin SeatMap template for Venue ID {}: {}", id, venue.getName());
        return mapToResponse(updated);
    }

    /** Chuyển địa điểm sang response; {@code isPublic} trống được coi là công khai. */
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
                .isPublic(venue.getIsPublic() != null ? venue.getIsPublic() : true)
                .organizerId(venue.getOrganizerId())
                .seatmapTemplate(venue.getSeatmapTemplate())
                .eventsHosted(venue.getEventsHosted())
                .createdAt(venue.getCreatedAt())
                .updatedAt(venue.getUpdatedAt())
                .build();
    }
}
