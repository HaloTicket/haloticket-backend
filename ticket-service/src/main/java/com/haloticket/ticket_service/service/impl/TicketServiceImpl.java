package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.util.TicketQuantities;
import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.exception.QuantityBelowSoldAndHeldException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
        boolean showtimeScoped = showtimeId != null && !showtimeId.isBlank();

        List<TicketType> all = ticketTypeRepository.findByEventId(eventId);
        boolean eventHasShowtimes = all.stream().anyMatch(TicketServiceImpl::hasShowtime);
        // scope = the rows this call may touch. An event-level sync never touches or creates master rows once the
        // event has showtime rows; with showtimes it only updates the per-showtime rows that match by id or name.
        List<TicketType> scope = new ArrayList<>();
        for (TicketType tt : all) {
            if (showtimeScoped ? showtimeId.equals(tt.getShowtimeId()) : (eventHasShowtimes == hasShowtime(tt))) {
                scope.add(tt);
            }
        }
        Map<String, TicketType> byId = new HashMap<>();
        Map<String, List<TicketType>> byName = new HashMap<>();
        for (TicketType tt : scope) {
            indexRow(byId, byName, tt);
        }

        Map<String, TicketType> processed = new LinkedHashMap<>();
        boolean createAllowed = showtimeScoped || !eventHasShowtimes;

        if (requests != null) {
            for (TicketTypeCreateRequest req : requests) {
                if (req == null) continue;
                req.setEventId(eventId);
                if (showtimeScoped) req.setShowtimeId(showtimeId);
                String requestedId = req.getId();
                String reqName = req.getName() != null ? req.getName().trim() : "";

                List<TicketType> targets = new ArrayList<>();
                // a rename is only recognised by id; otherwise a name match is the natural key
                if (requestedId != null && !requestedId.isBlank() && !requestedId.startsWith("tier_") && byId.containsKey(requestedId)) {
                    TicketType direct = byId.get(requestedId);
                    String newKey = normalizeName(reqName);
                    if (!reqName.isEmpty() && !normalizeName(direct.getName()).equals(newKey)) {
                        for (TicketType other : byName.getOrDefault(newKey, List.of())) {
                            if (!other.getId().equals(direct.getId()) && Objects.equals(other.getShowtimeId(), direct.getShowtimeId())) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NAME_CONFLICT");
                            }
                        }
                    }
                    targets.add(direct);
                } else if (!reqName.isEmpty()) {
                    targets.addAll(byName.getOrDefault(normalizeName(reqName), List.of()));
                }

                if (!targets.isEmpty()) {
                    for (TicketType existing : targets) {
                        applyToExisting(existing, req, reqName);
                        processed.put(existing.getId(), existing);
                    }
                } else if (createAllowed) {
                    req.setId(null);
                    TicketType created = ticketTypeRepository.save(mapToEntity(req));
                    processed.put(created.getId(), created);
                    indexRow(byId, byName, created);
                }
            }
        }

        // rows of the scope that the request no longer lists are removed, or hidden when sales or holds reference them
        for (TicketType tt : scope) {
            if (processed.containsKey(tt.getId())) continue;
            boolean inUse = (tt.getReservedQuantity() != null && tt.getReservedQuantity() > 0)
                    || (tt.getInitialQuantity() != null && tt.getTotalQuantity() != null && tt.getInitialQuantity() > tt.getTotalQuantity())
                    || ticketRepository.existsByTicketType_Id(tt.getId());
            if (inUse) {
                TicketType fresh = ticketTypeRepository.findById(tt.getId()).orElse(null);
                if (fresh != null) {
                    fresh.setTicketTypeStatus(TicketTypeStatus.HIDDEN);
                    ticketTypeRepository.save(fresh);
                }
            } else {
                ticketTypeRepository.deleteById(tt.getId());
            }
        }

        List<TicketTypeResponse> responses = new ArrayList<>();
        Set<String> namesInResponse = new HashSet<>();
        for (String id : processed.keySet()) {
            TicketType fresh = ticketTypeRepository.findById(id).orElse(null);
            if (fresh != null && namesInResponse.add(normalizeName(fresh.getName()))) {
                responses.add(mapToResponse(fresh));
            }
        }
        return responses;
    }

    /** One atomic UPDATE per existing row; 0 rows means the new issued number is below sold + held. */
    private void applyToExisting(TicketType existing, TicketTypeCreateRequest req, String reqName) {
        String name = reqName.isEmpty() ? existing.getName() : reqName;
        Double price = req.getPrice() != null ? req.getPrice() : existing.getPrice();
        Boolean isFree = req.getIsFree() != null ? req.getIsFree() : (price != null && price == 0.0);
        Integer min = req.getMinPerOrder() != null ? req.getMinPerOrder() : (existing.getMinPerOrder() != null ? existing.getMinPerOrder() : 1);
        Integer max = req.getMaxPerOrder() != null ? req.getMaxPerOrder() : (existing.getMaxPerOrder() != null ? existing.getMaxPerOrder() : 5);
        String description = req.getDescription() != null ? req.getDescription() : existing.getDescription();
        Integer newInitial = req.getInitialQuantity() != null ? req.getInitialQuantity() : req.getTotalQuantity();
        LocalDateTime now = LocalDateTime.now();

        if (newInitial == null) {
            ticketTypeRepository.applyDetailsAtomic(existing.getId(), name, price, isFree, min, max, description, now);
            return;
        }
        if (newInitial < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        }
        if (existing.getInitialQuantity() == null) {
            ticketTypeRepository.backfillInitialQuantityAtomic(existing.getId()); // so tickets already sold are not forgotten below
        }
        int updated = ticketTypeRepository.applyIssuedQuantityAtomic(existing.getId(), newInitial, name, price, isFree, min, max, description, now);
        if (updated == 0) {
            TicketType current = ticketTypeRepository.findById(existing.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_TYPE_CHANGED"));
            Integer initial = current.getInitialQuantity();
            Integer total = current.getTotalQuantity();
            int sold = initial == null || total == null ? 0 : Math.max(0, initial - total);
            throw new QuantityBelowSoldAndHeldException(current.getId(), current.getName(), sold,
                    TicketQuantities.held(current.getReservedQuantity()));
        }
    }

    private static boolean hasShowtime(TicketType tt) {
        return tt.getShowtimeId() != null && !tt.getShowtimeId().isBlank();
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static void indexRow(Map<String, TicketType> byId, Map<String, List<TicketType>> byName, TicketType tt) {
        byId.put(tt.getId(), tt);
        byName.computeIfAbsent(normalizeName(tt.getName()), k -> new ArrayList<>()).add(tt);
    }

    /** With showtimes the list is the per-showtime rows (never a showtime-less master row); without, the master rows. */
    private List<TicketType> visibleRows(String eventId) {
        List<TicketType> list = ticketTypeRepository.findByEventId(eventId).stream()
                .filter(tt -> tt.getTicketTypeStatus() != TicketTypeStatus.HIDDEN)
                .toList();
        if (list.stream().anyMatch(TicketServiceImpl::hasShowtime)) {
            return list.stream().filter(TicketServiceImpl::hasShowtime).toList();
        }
        return list;
    }

    @Override
    public List<TicketTypeResponse> getTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        return visibleRows(eventId).stream().map(this::mapToResponse).toList();
    }

    @Override
    public List<TicketTypeResponse> getAllTicketTypesByEventId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty())
            return List.of();
        return visibleRows(eventId).stream().map(this::mapToResponse).toList();
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
        // new rows start with nothing sold: total = initial = the issued number (fallback to the legacy field, then 100)
        int issued = req.getInitialQuantity() != null ? req.getInitialQuantity()
                : (req.getTotalQuantity() != null ? req.getTotalQuantity() : 100);

        return TicketType.builder()
                .id(existingId)
                .eventId(req.getEventId())
                .showtimeId(req.getShowtimeId())
                .name(req.getName() != null ? req.getName() : "Vé tiêu chuẩn")
                .price(req.getPrice() != null ? req.getPrice() : 0.0)
                .isFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0))
                .totalQuantity(issued)
                .initialQuantity(issued)
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
                .availableQuantity(TicketQuantities.available(entity.getTotalQuantity(), entity.getReservedQuantity()))
                .remainingQuantity(TicketQuantities.remaining(entity.getTotalQuantity()))
                .heldQuantity(TicketQuantities.held(entity.getReservedQuantity()))
                .soldQuantity(TicketQuantities.sold(entity.getInitialQuantity(), entity.getTotalQuantity()))
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