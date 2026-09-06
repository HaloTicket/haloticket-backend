package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;

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

        return TicketType.builder()
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
}