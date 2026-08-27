package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;

public interface TicketService {
    TicketTypeResponse createTicketType(TicketTypeCreateRequest request);
}