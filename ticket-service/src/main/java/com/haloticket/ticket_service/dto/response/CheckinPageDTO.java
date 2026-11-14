package com.haloticket.ticket_service.dto.response;

import java.util.List;

/** One page of the people who already entered, newest first. */
public record CheckinPageDTO(List<TicketCheckinDTO> content, int page, int size, long totalElements, int totalPages) {}
