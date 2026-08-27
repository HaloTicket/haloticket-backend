package com.haloticket.ticket_service.service.impl;

import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketTypeRepository ticketTypeRepository;

    @Override
    public TicketTypeResponse createTicketType(TicketTypeCreateRequest request) {
        return null;
    }
}