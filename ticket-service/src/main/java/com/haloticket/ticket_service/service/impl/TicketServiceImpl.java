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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatZoneRepository seatZoneRepository;

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

        // remove existing ticket types for event to synchronize fresh set
        ticketTypeRepository.deleteByEventId(eventId);

        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        List<TicketTypeResponse> responses = new ArrayList<>();
        for (TicketTypeCreateRequest req : requests) {
            req.setEventId(eventId);
            TicketType entity = mapToEntity(req);
            TicketType saved = ticketTypeRepository.save(entity);
            responses.add(mapToResponse(saved));
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
                    return java.time.OffsetDateTime.parse(s).toLocalDateTime();
                } catch (Exception ex2) {
                    try {
                        return java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
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
        Optional<SeatLayout> layoutOpt = seatLayoutRepository.findByEventId(eventId);
        if (layoutOpt.isEmpty()) {
            return null;
        }
        SeatLayout layout = layoutOpt.get();
        layout.setSeatZones(seatZoneRepository.findBySeatLayoutId(layout.getId()));
        return mapLayoutToResponse(layout);
    }

    private SeatLayoutResponse processSaveSeatLayout(String eventId, SeatLayoutRequest request, SeatLayoutStatus status) {
        String targetEventId = (eventId != null && !eventId.trim().isEmpty()) ? eventId : request.getEventId();
        if (targetEventId == null || targetEventId.trim().isEmpty()) {
            throw new IllegalArgumentException("eventId is required for SeatLayout");
        }

        SeatLayout layout = seatLayoutRepository.findByEventId(targetEventId)
                .orElseGet(() -> SeatLayout.builder()
                        .eventId(targetEventId)
                        .status(status)
                        .build());

        layout.setStatus(status);
        if (request.getDraftJson() != null) layout.setDraftJson(request.getDraftJson());
        if (request.getSvgData() != null) layout.setSvgData(request.getSvgData());
        if (request.getImageUrl() != null) layout.setImageUrl(request.getImageUrl());

        SeatLayout savedLayout = seatLayoutRepository.save(layout);

        if (request.getZones() != null) {
            // Remove existing zones cleanly for layout
            seatZoneRepository.deleteBySeatLayoutId(savedLayout.getId());

            List<SeatZone> zoneEntities = new ArrayList<>();
            for (SeatLayoutRequest.ZoneDto zDto : request.getZones()) {
                SeatZone zone = SeatZone.builder()
                        .seatLayout(savedLayout)
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
                zoneEntities.add(zone);
            }
            List<SeatZone> savedZones = seatZoneRepository.saveAll(zoneEntities);
            savedLayout.setSeatZones(savedZones);
        } else {
            savedLayout.setSeatZones(seatZoneRepository.findBySeatLayoutId(savedLayout.getId()));
        }

        return mapLayoutToResponse(savedLayout);
    }

    private SeatLayoutResponse mapLayoutToResponse(SeatLayout layout) {
        if (layout == null) return null;
        List<SeatLayoutResponse.ZoneResponseDto> zoneDtos = new ArrayList<>();
        if (layout.getSeatZones() != null) {
            for (SeatZone z : layout.getSeatZones()) {
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
}