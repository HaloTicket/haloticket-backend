package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.repository.SeatLayoutRepository;
import com.haloticket.ticket_service.repository.SeatZoneRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;
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
    public List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, List<TicketTypeCreateRequest> requests) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return List.of();
        }

        List<TicketType> existingTicketTypes = ticketTypeRepository.findByEventId(eventId);
        Map<String, TicketType> existingMap = new HashMap<>();
        for (TicketType tt : existingTicketTypes) {
            existingMap.put(tt.getId(), tt);
        }

        List<TicketTypeResponse> responses = new ArrayList<>();
        Set<String> processedIds = new HashSet<>();

        if (requests != null && !requests.isEmpty()) {
            for (TicketTypeCreateRequest req : requests) {
                req.setEventId(eventId);
                String requestedId = req.getId();

                if (requestedId != null && !requestedId.startsWith("tier_") && existingMap.containsKey(requestedId)) {
                    TicketType entity = existingMap.get(requestedId);
                    LocalDateTime startSale = parseDateTime(req.getStartSaleDate());
                    LocalDateTime endSale = parseDateTime(req.getEndSaleDate());

                    if (startSale != null && endSale != null && (endSale.isBefore(startSale) || endSale.isEqual(startSale))) {
                        throw new IllegalArgumentException("Ngày kết thúc bán vé phải sau ngày bắt đầu bán vé cho loại vé: " + req.getName());
                    }

                    entity.setName(req.getName() != null ? req.getName() : "Vé tiêu chuẩn");
                    entity.setPrice(req.getPrice() != null ? req.getPrice() : 0.0);
                    entity.setIsFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0));
                    entity.setTotalQuantity(req.getTotalQuantity() != null ? req.getTotalQuantity() : 100);
                    entity.setMinPerOrder(req.getMinPerOrder() != null ? req.getMinPerOrder() : 1);
                    entity.setMaxPerOrder(req.getMaxPerOrder() != null ? req.getMaxPerOrder() : 10);
                    if (startSale != null) entity.setStartSaleDate(startSale);
                    if (endSale != null) entity.setEndSaleDate(endSale);
                    if (req.getDescription() != null) entity.setDescription(req.getDescription());

                    TicketType saved = ticketTypeRepository.save(entity);
                    processedIds.add(saved.getId());
                    responses.add(mapToResponse(saved));
                } else {
                    req.setId(null);
                    TicketType entity = mapToEntity(req);
                    TicketType saved = ticketTypeRepository.save(entity);
                    processedIds.add(saved.getId());
                    responses.add(mapToResponse(saved));
                }
            }
        }

        for (TicketType tt : existingTicketTypes) {
            if (!processedIds.contains(tt.getId())) {
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
        return ticketTypeRepository.findByEventId(eventId).stream()
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
        LocalDateTime startSale = parseDateTime(req.getStartSaleDate());
        LocalDateTime endSale = parseDateTime(req.getEndSaleDate());

        if (startSale != null && endSale != null) {
            if (endSale.isBefore(startSale) || endSale.isEqual(startSale)) {
                throw new IllegalArgumentException(
                        "Ngày kết thúc bán vé phải sau ngày bắt đầu bán vé cho loại vé: " + req.getName());
            }
        }

        String existingId = (req.getId() != null && !req.getId().startsWith("tier_") && !req.getId().isBlank()) ? req.getId() : null;

        return TicketType.builder()
                .id(existingId)
                .eventId(req.getEventId())
                .name(req.getName() != null ? req.getName() : "Vé tiêu chuẩn")
                .price(req.getPrice() != null ? req.getPrice() : 0.0)
                .isFree(req.getIsFree() != null ? req.getIsFree() : (req.getPrice() != null && req.getPrice() == 0.0))
                .totalQuantity(req.getTotalQuantity() != null ? req.getTotalQuantity() : 100)
                .minPerOrder(req.getMinPerOrder() != null ? req.getMinPerOrder() : 1)
                .maxPerOrder(req.getMaxPerOrder() != null ? req.getMaxPerOrder() : 10)
                .startSaleDate(startSale)
                .endSaleDate(endSale)
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
                .name(entity.getName())
                .price(entity.getPrice())
                .isFree(entity.getIsFree())
                .totalQuantity(entity.getTotalQuantity())
                .minPerOrder(entity.getMinPerOrder())
                .maxPerOrder(entity.getMaxPerOrder())
                .startSaleDate(entity.getStartSaleDate() != null ? entity.getStartSaleDate().toString() : null)
                .endSaleDate(entity.getEndSaleDate() != null ? entity.getEndSaleDate().toString() : null)
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

    private SeatLayoutResponse mapLayoutToResponse(SeatLayout layout) {
        return mapLayoutToResponse(layout, layout != null ? layout.getSeatZones() : null);
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
    public Map<String, String> getSeatStatusMap(String eventId) {
        Map<String, String> statusMap = new HashMap<>();
        if (eventId == null || eventId.isBlank()) {
            return statusMap;
        }

        try {
            // check Redis Set for held seats
            Set<String> heldSeats = stringRedisTemplate.opsForSet().members("event:" + eventId + ":held_seats");
            if (heldSeats != null && !heldSeats.isEmpty()) {
                for (String seatId : heldSeats) {
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // fallback for individual seat:hold keys
            Set<String> holdKeys = stringRedisTemplate.keys("seat:hold:" + eventId + ":*");
            if (holdKeys != null) {
                for (String key : holdKeys) {
                    String seatId = key.substring(("seat:hold:" + eventId + ":").length());
                    statusMap.put(seatId, "HOLDING");
                }
            }

            // check Redis Set for sold seats
            Set<String> soldSeats = stringRedisTemplate.opsForSet().members("event:" + eventId + ":sold_seats");
            if (soldSeats != null && !soldSeats.isEmpty()) {
                for (String seatId : soldSeats) {
                    statusMap.put(seatId, "SOLD");
                }
            }

            // fallback for individual seat:sold keys
            Set<String> soldKeys = stringRedisTemplate.keys("seat:sold:" + eventId + ":*");
            if (soldKeys != null) {
                for (String key : soldKeys) {
                    String seatId = key.substring(("seat:sold:" + eventId + ":").length());
                    statusMap.put(seatId, "SOLD");
                }
            }
        } catch (Exception e) {
            // fallback if Redis fails
        }

        return statusMap;
    }
}