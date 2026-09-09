package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;

import java.util.List;

public interface TicketService {
    TicketTypeResponse createTicketType(TicketTypeCreateRequest request);
    List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, List<TicketTypeCreateRequest> requests);
    List<TicketTypeResponse> getTicketTypesByEventId(String eventId);

    SeatLayoutResponse saveSeatLayoutDraft(String eventId, SeatLayoutRequest request);
    SeatLayoutResponse publishSeatLayout(String eventId, SeatLayoutRequest request);
    SeatLayoutResponse getSeatLayoutByEventId(String eventId);
}