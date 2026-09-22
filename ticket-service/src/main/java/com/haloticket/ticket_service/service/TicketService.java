package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.SeatLayoutRequest;
import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.SeatLayoutResponse;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.dto.response.OwnedTicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface TicketService {
    TicketTypeResponse createTicketType(TicketTypeCreateRequest request);
    List<TicketTypeResponse> syncTicketTypesForEvent(String eventId, List<TicketTypeCreateRequest> requests);
    List<TicketTypeResponse> getTicketTypesByEventId(String eventId);

    SeatLayoutResponse saveSeatLayoutDraft(String eventId, SeatLayoutRequest request);
    SeatLayoutResponse publishSeatLayout(String eventId, SeatLayoutRequest request);
    SeatLayoutResponse getSeatLayoutByEventId(String eventId);
    Map<String, String> getSeatStatusMap(String eventId);
    
    Page<OwnedTicketResponse> getMyTickets(String userId, Pageable pageable);
    OwnedTicketResponse getMyTicket(String ticketId, String userId);
}