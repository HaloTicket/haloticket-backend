package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.repository.SeatLayoutRepository;
import com.haloticket.ticket_service.repository.SeatZoneRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;
    private final TicketRepository ticketRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatZoneRepository seatZoneRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public TicketTypeResponse createTicketType(TicketTypeCreateRequest request) {
        if (request == null)
            return null;
        TicketType entity = mapToEntity(request);
        TicketType saved = ticketTypeRepository.save(entity);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, String showtimeId, List<TicketTypeCreateRequest> requests) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return List.of();
        }

        List<TicketType> existingTicketTypes;
        if (showtimeId != null) {
            existingTicketTypes = ticketTypeRepository.findByEventIdAndShowtimeId(eventId, showtimeId);
        } else {
            existingTicketTypes = ticketTypeRepository.findByEventId(eventId);
        }
        Map<String, TicketType> existingMap = new HashMap<>();
        for (TicketType tt : existingTicketTypes) {
            existingMap.put(tt.getId(), tt);
        }

        List<TicketTypeResponse> responses = new ArrayList<>();
        Set<String> processedIds = new HashSet<>();
        Map<String, String> renamedFromOldToNew = new HashMap<>();

        // if doing event-level sync (showtimeId == null), identify all showtimes for this event
        Set<String> allShowtimeIds = new HashSet<>();
        if (showtimeId == null) {
            for (TicketType tt : existingTicketTypes) {
                if (tt.getShowtimeId() != null && !tt.getShowtimeId().isBlank()) {
                    allShowtimeIds.add(tt.getShowtimeId());
                }
            }
        }

        if (requests != null && !requests.isEmpty()) {
            // first pass: detect renames from requests matching by ID
            for (TicketTypeCreateRequest req : requests) {
                String requestedId = req.getId();
                if (requestedId != null && !requestedId.startsWith("tier_") && existingMap.containsKey(requestedId)) {
                    TicketType oldTt = existingMap.get(requestedId);
                    if (oldTt.getName() != null && req.getName() != null && !oldTt.getName().trim().equalsIgnoreCase(req.getName().trim())) {
                        renamedFromOldToNew.put(oldTt.getName().trim().toLowerCase(), req.getName().trim());
                    }
                }
            }

            // second pass: handle unmatched requests vs unmatched existing distinct tiers (rename by elimination)
            Set<String> requestNames = requests.stream()
                    .map(r -> r.getName() != null ? r.getName().trim().toLowerCase() : "")
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            Set<String> existingDistinctNames = existingTicketTypes.stream()
                    .map(t -> t.getName() != null ? t.getName().trim().toLowerCase() : "")
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            List<String> unmatchedReqNames = requestNames.stream()
                    .filter(rn -> !existingDistinctNames.contains(rn))
                    .collect(Collectors.toList());
            List<String> unmatchedOldNames = existingDistinctNames.stream()
                    .filter(en -> !requestNames.contains(en))
                    .collect(Collectors.toList());
            if (unmatchedReqNames.size() == 1 && unmatchedOldNames.size() == 1) {
                String oldNorm = unmatchedOldNames.get(0);
                String newName = requests.stream()
                        .filter(r -> r.getName() != null && r.getName().trim().equalsIgnoreCase(unmatchedReqNames.get(0)))
                        .map(r -> r.getName().trim())
                        .findFirst()
                        .orElse(unmatchedReqNames.get(0));
                renamedFromOldToNew.put(oldNorm, newName);
            }

            for (TicketTypeCreateRequest req : requests) {
                req.setEventId(eventId);
                if (showtimeId != null) req.setShowtimeId(showtimeId);
                String requestedId = req.getId();
                List<TicketType> matchedEntities = new ArrayList<>();
                Set<String> matchedIdsForThisReq = new HashSet<>();

                if (requestedId != null && !requestedId.startsWith("tier_") && existingMap.containsKey(requestedId)) {
                    TicketType directMatch = existingMap.get(requestedId);
                    matchedEntities.add(directMatch);
                    matchedIdsForThisReq.add(directMatch.getId());
                }

                if (req.getName() != null && !req.getName().trim().isEmpty()) {
                    String normName = req.getName().trim();
                    for (TicketType existing : existingTicketTypes) {
                        if (!matchedIdsForThisReq.contains(existing.getId())) {
                            if (existing.getName() != null && existing.getName().trim().equalsIgnoreCase(normName)) {
                                matchedEntities.add(existing);
                                matchedIdsForThisReq.add(existing.getId());
                            }
                        }
                    }

                    // also check if any existing was renamed to this new name
                    for (Map.Entry<String, String> entry : renamedFromOldToNew.entrySet()) {
                        if (entry.getValue().equalsIgnoreCase(normName)) {
                            String oldNorm = entry.getKey();
                            for (TicketType existing : existingTicketTypes) {
                                if (!matchedIdsForThisReq.contains(existing.getId())) {
                                    if (existing.getName() != null && existing.getName().trim().equalsIgnoreCase(oldNorm)) {
                                        matchedEntities.add(existing);
                                        matchedIdsForThisReq.add(existing.getId());
                                    }
                                }
                            }
                        }
                    }
                }

                // If global event-level sync, ensure every showtime has this tier
                if (showtimeId == null && !allShowtimeIds.isEmpty()) {
                    Set<String> coveredShowtimeIds = matchedEntities.stream()
                            .map(TicketType::getShowtimeId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    for (String stId : allShowtimeIds) {
                        if (!coveredShowtimeIds.contains(stId)) {
                            TicketType stEntity = mapToEntity(req);
                            stEntity.setId(null);
                            stEntity.setShowtimeId(stId);
                            stEntity.setTicketTypeStatus(TicketTypeStatus.ACTIVE);
                            matchedEntities.add(stEntity);
                        }
                    }
                }

                if (!matchedEntities.isEmpty()) {
                    for (TicketType entity : matchedEntities) {
                        entity.setName(req.getName() != null ? req.getName().trim() : "Vé tiêu chuẩn");
                        entity.setPrice(req.getPrice() != null ? req.getPrice() : 0.0);
                        entity.setIsFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0));
                        entity.setTotalQuantity(req.getTotalQuantity() != null ? req.getTotalQuantity() : 100);
                        entity.setMinPerOrder(req.getMinPerOrder() != null ? req.getMinPerOrder() : 1);
                        entity.setMaxPerOrder(req.getMaxPerOrder() != null ? req.getMaxPerOrder() : 5);
                        entity.setTicketTypeStatus(TicketTypeStatus.ACTIVE);
                        if (entity.getInitialQuantity() == null && entity.getTotalQuantity() != null) {
                            entity.setInitialQuantity(entity.getTotalQuantity());
                        }
                        if (req.getDescription() != null) entity.setDescription(req.getDescription());
                        if (showtimeId != null) entity.setShowtimeId(showtimeId);

                        TicketType saved = ticketTypeRepository.save(entity);
                        processedIds.add(saved.getId());

                        boolean nameAlreadyInResponse = false;
                        for (TicketTypeResponse res : responses) {
                            if (res.getName() != null && res.getName().equalsIgnoreCase(saved.getName())) {
                                nameAlreadyInResponse = true;
                                break;
                            }
                        }
                        if (!nameAlreadyInResponse) {
                            responses.add(mapToResponse(saved));
                        }
                    }
                } else {
                    req.setId(null);
                    TicketType newEntity = mapToEntity(req);
                    newEntity.setTicketTypeStatus(TicketTypeStatus.ACTIVE);
                    TicketType saved = ticketTypeRepository.save(newEntity);
                    processedIds.add(saved.getId());
                    responses.add(mapToResponse(saved));

                    // if global sync and showtimes already exist, replicate this new tier to all showtimes
                    if (showtimeId == null && !allShowtimeIds.isEmpty()) {
                        for (String stId : allShowtimeIds) {
                            TicketType stEntity = mapToEntity(req);
                            stEntity.setId(null);
                            stEntity.setShowtimeId(stId);
                            stEntity.setTicketTypeStatus(TicketTypeStatus.ACTIVE);
                            TicketType savedSt = ticketTypeRepository.save(stEntity);
                            processedIds.add(savedSt.getId());
                        }
                    }
                }
            }
        }

        // handle existing tickets not in processedIds
        for (TicketType tt : existingTicketTypes) {
            if (!processedIds.contains(tt.getId())) {
                // if it was renamed, update its name and mark processed
                String oldNorm = tt.getName() != null ? tt.getName().trim().toLowerCase() : "";
                if (renamedFromOldToNew.containsKey(oldNorm)) {
                    tt.setName(renamedFromOldToNew.get(oldNorm));
                    tt.setTicketTypeStatus(TicketTypeStatus.ACTIVE);
                    ticketTypeRepository.save(tt);
                    processedIds.add(tt.getId());
                    continue;
                }

                // if doing event-level sync, also clean up deleted tiers from showtimes
                try {
                    ticketTypeRepository.delete(tt);
                } catch (Exception e) {
                    tt.setTicketTypeStatus(TicketTypeStatus.HIDDEN);
                    ticketTypeRepository.save(tt);
                }
            }
        }

        return responses;
    }

    @Override
    public List<TicketTypeResponse> getTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        List<TicketType> list = ticketTypeRepository.findByEventId(eventId);
        Map<String, TicketType> distinctMap = new LinkedHashMap<>();
        for (TicketType tt : list) {
            if (tt.getTicketTypeStatus() == TicketTypeStatus.HIDDEN) {
                continue;
            }
            String key = tt.getName() != null ? tt.getName().trim().toLowerCase() : tt.getId();
            if (!distinctMap.containsKey(key)) {
                distinctMap.put(key, tt);
            } else {
                TicketType existing = distinctMap.get(key);
                if (existing.getShowtimeId() != null && tt.getShowtimeId() == null) {
                    distinctMap.put(key, tt);
                } else if (tt.getUpdatedAt() != null && (existing.getUpdatedAt() == null || tt.getUpdatedAt().isAfter(existing.getUpdatedAt()))) {
                    distinctMap.put(key, tt);
                }
            }
        }
        return distinctMap.values().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public List<TicketTypeResponse> getAllTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        List<TicketType> list = ticketTypeRepository.findByEventId(eventId);
        return list.stream()
                .filter(tt -> tt.getTicketTypeStatus() != TicketTypeStatus.HIDDEN)
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public TicketTypeResponse getTicketTypeById(String id) {
        if (id == null || id.trim().isEmpty())
            return null;
        return ticketTypeRepository.findById(id)
                .map(this::mapToResponse)
                .orElse(null);
    }

    @Override
    public List<TicketTypeResponse> getTicketTypesByShowtimeId(String showtimeId) {
        if (showtimeId == null || showtimeId.trim().isEmpty())
            return List.of();
        List<TicketType> list = ticketTypeRepository.findByShowtimeId(showtimeId);
        Map<String, TicketType> distinctMap = new LinkedHashMap<>();
        for (TicketType tt : list) {
            if (tt.getTicketTypeStatus() == TicketTypeStatus.HIDDEN) {
                continue;
            }
            String key = tt.getName() != null ? tt.getName().trim().toLowerCase() : tt.getId();
            distinctMap.putIfAbsent(key, tt);
        }
        return distinctMap.values().stream()
                .map(this::mapToResponse)
                .toList();
    }

    private LocalDateTime parseDateTime(String str) {
        if (str == null || str.trim().isEmpty())
            return null;
        String s = str.trim();
        if (s.contains(" ") && !s.contains("T")) {
            s = s.replace(" ", "T");
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s + ":00");
            } catch (Exception ex) {
                try {
                    return OffsetDateTime.parse(s).toLocalDateTime();
                } catch (Exception ex2) {
                    try {
                        return Instant.parse(s).atZone(ZoneId.systemDefault()).toLocalDateTime();
                    } catch (Exception ex3) {
                        return null;
                    }
                }
            }
        }
    }

    private TicketType mapToEntity(TicketTypeCreateRequest req) {

        String existingId = (req.getId() != null && !req.getId().startsWith("tier_") && !req.getId().isBlank()) ? req.getId() : null;

        return TicketType.builder()
                .id(existingId)
                .eventId(req.getEventId())
                .showtimeId(req.getShowtimeId())
                .name(req.getName() != null ? req.getName() : "Vé tiêu chuẩn")
                .price(req.getPrice() != null ? req.getPrice() : 0.0)
                .isFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0))
                .totalQuantity(req.getTotalQuantity() != null ? req.getTotalQuantity() : 100)
                .initialQuantity(req.getTotalQuantity() != null ? req.getTotalQuantity() : 100)
                .minPerOrder(req.getMinPerOrder() != null ? req.getMinPerOrder() : 1)
                .maxPerOrder(req.getMaxPerOrder() != null ? req.getMaxPerOrder() : 10)
                .description(req.getDescription())
                .ticketTypeStatus(TicketTypeStatus.ACTIVE)
                .build();
    }

    private TicketTypeResponse mapToResponse(TicketType entity) {
        if (entity == null)
            return null;
        return TicketTypeResponse.builder()
                .id(entity.getId())
                .eventId(entity.getEventId())
                .showtimeId(entity.getShowtimeId())
                .name(entity.getName())
                .price(entity.getPrice())
                .isFree(entity.getIsFree())
                .totalQuantity(entity.getTotalQuantity())
                .initialQuantity(entity.getInitialQuantity())
                .availableQuantity(entity.getTotalQuantity() == null ? null
                        : Math.max(0, entity.getTotalQuantity() - (entity.getReservedQuantity() == null ? 0 : entity.getReservedQuantity())))
                .minPerOrder(entity.getMinPerOrder())
                .maxPerOrder(entity.getMaxPerOrder())
                .description(entity.getDescription())
                .status(entity.getTicketTypeStatus())
                .build();
    }

    @Override
    @Transactional
    public SeatLayoutResponse saveSeatLayoutDraft(String eventId, SeatLayoutRequest request) {
        return processSaveSeatLayout(eventId, request, SeatLayoutStatus.DRAFT);
    }

    @Override
    @Transactional
    public SeatLayoutResponse publishSeatLayout(String eventId, SeatLayoutRequest request) {
        return processSaveSeatLayout(eventId, request, SeatLayoutStatus.PUBLISHED);
    }

    @Override
    @Transactional(readOnly = true)
    public SeatLayoutResponse getSeatLayoutByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return null;
        }
        List<SeatLayout> layouts = seatLayoutRepository.findByEventId(eventId);
        if (layouts.isEmpty()) {
            return null;
        }
        SeatLayout layout = layouts.stream()
                .max(Comparator.comparing(l -> l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt()))
                .orElse(layouts.get(0));

        List<SeatZone> zones = seatZoneRepository.findBySeatLayoutId(layout.getId());
        return mapLayoutToResponse(layout, zones);
    }

    private SeatLayoutResponse processSaveSeatLayout(String eventId, SeatLayoutRequest request, SeatLayoutStatus status) {
        String targetEventId = (eventId != null && !eventId.trim().isEmpty()) ? eventId : request.getEventId();
        if (targetEventId == null || targetEventId.trim().isEmpty()) {
            throw new IllegalArgumentException("eventId is required for SeatLayout");
        }

        List<SeatLayout> existingLayouts = seatLayoutRepository.findByEventId(targetEventId);
        SeatLayout layout;
        if (!existingLayouts.isEmpty()) {
            layout = existingLayouts.stream()
                    .max(Comparator.comparing(l -> l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getCreatedAt()))
                    .orElse(existingLayouts.get(0));

            for (SeatLayout old : existingLayouts) {
                if (!old.getId().equals(layout.getId())) {
                    try {
                        seatLayoutRepository.delete(old);
                    } catch (Exception ignored) {}
                }
            }
        } else {
            layout = SeatLayout.builder()
                    .eventId(targetEventId)
                    .status(status)
                    .seatZones(new ArrayList<>())
                    .build();
        }

        layout.setStatus(status);
        if (request.getDraftJson() != null) layout.setDraftJson(request.getDraftJson());
        if (request.getSvgData() != null) layout.setSvgData(request.getSvgData());
        if (request.getImageUrl() != null) layout.setImageUrl(request.getImageUrl());

        if (request.getZones() != null) {
            if (layout.getSeatZones() == null) {
                layout.setSeatZones(new ArrayList<>());
            } else {
                layout.getSeatZones().clear();
            }

            for (SeatLayoutRequest.ZoneDto zDto : request.getZones()) {
                SeatZone zone = SeatZone.builder()
                        .seatLayout(layout)
                        .name(zDto.getName() != null ? zDto.getName() : "Khu vực mới")
                        .ticketTypeId(zDto.getTicketTypeId())
                        .shapeType(zDto.getShapeType() != null ? zDto.getShapeType() : "RECTANGLE")
                        .colorCode(zDto.getColorCode())
                        .fillColor(zDto.getFillColor())
                        .strokeColor(zDto.getStrokeColor())
                        .curvature(zDto.getCurvature())
                        .x(zDto.getX())
                        .y(zDto.getY())
                        .width(zDto.getWidth())
                        .height(zDto.getHeight())
                        .rotation(zDto.getRotation())
                        .points(zDto.getPoints())
                        .capacity(zDto.getCapacity())
                        .build();
                layout.getSeatZones().add(zone);
            }
        }

        SeatLayout savedLayout = seatLayoutRepository.save(layout);
        return mapLayoutToResponse(savedLayout, savedLayout.getSeatZones());
    }

    private SeatLayoutResponse mapLayoutToResponse(SeatLayout layout, List<SeatZone> zones) {
        if (layout == null) return null;
        List<SeatLayoutResponse.ZoneResponseDto> zoneDtos = new ArrayList<>();
        List<SeatZone> seatZones = zones != null ? zones : (layout.getSeatZones() != null ? layout.getSeatZones() : List.of());
        for (SeatZone z : seatZones) {
            zoneDtos.add(SeatLayoutResponse.ZoneResponseDto.builder()
                    .id(z.getId())
                    .name(z.getName())
                    .ticketTypeId(z.getTicketTypeId())
                    .shapeType(z.getShapeType())
                    .colorCode(z.getColorCode())
                    .fillColor(z.getFillColor())
                    .strokeColor(z.getStrokeColor())
                    .curvature(z.getCurvature())
                    .x(z.getX())
                    .y(z.getY())
                    .width(z.getWidth())
                    .height(z.getHeight())
                    .rotation(z.getRotation())
                    .points(z.getPoints())
                    .capacity(z.getCapacity())
                    .build());
        }

        return SeatLayoutResponse.builder()
                .id(layout.getId())
                .eventId(layout.getEventId())
                .draftJson(layout.getDraftJson())
                .svgData(layout.getSvgData())
                .imageUrl(layout.getImageUrl())
                .status(layout.getStatus() != null ? layout.getStatus().name() : null)
                .zones(zoneDtos)
                .build();
    }

    @Override
    public Map<String, String> getSeatStatusMap(String eventId, String showtimeId) {
        Map<String, String> statusMap = new HashMap<>();
        if (eventId == null || eventId.isBlank()) {
            return statusMap;
        }

        try {
            String showtimePrefix = showtimeId != null ? "showtime:" + showtimeId : "event:" + eventId;
            // check Redis Set for held seats
            Set<String> heldSeats = stringRedisTemplate.opsForSet().members(showtimePrefix + ":held_seats");
            if (heldSeats != null && !heldSeats.isEmpty()) {
                for (String seatId : heldSeats) {
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // fallback for individual seat:hold keys
            Set<String> holdKeys = stringRedisTemplate.keys("seat:hold:" + showtimePrefix + ":*");
            if (holdKeys != null) {
                for (String key : holdKeys) {
                    String seatId = key.substring(("seat:hold:" + showtimePrefix + ":").length());
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // check Redis Set for sold seats
            Set<String> soldSeats = stringRedisTemplate.opsForSet().members(showtimePrefix + ":sold_seats");
            if (soldSeats != null && !soldSeats.isEmpty()) {
                for (String seatId : soldSeats) {
                    statusMap.put(seatId, "SOLD");
                }
            }

            // fallback for individual seat:sold keys
            Set<String> soldKeys = stringRedisTemplate.keys("seat:sold:" + showtimePrefix + ":*");
            if (soldKeys != null) {
                for (String key : soldKeys) {
                    String seatId = key.substring(("seat:sold:" + showtimePrefix + ":").length());
                    statusMap.put(seatId, "SOLD");
                }
            }
        } catch (Exception e) {
            // fallback if Redis fails
        }

        return statusMap;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OwnedTicketResponse> getMyTickets(String userId, Pageable pageable) {
        if (userId == null || userId.isBlank()) return Page.empty();
        return ticketRepository.findByOwnerIdOrderByIssuedAtDesc(userId, pageable)
                .map(OwnedTicketResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnedTicketResponse getMyTicket(String ticketId, String userId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        if (!userId.equals(ticket.getOwnerId())) {
            throw new RuntimeException("Unauthorized");
        }
        return OwnedTicketResponse.from(ticket);
    }
}