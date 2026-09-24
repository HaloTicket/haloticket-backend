package com.haloticket.event_service.dto.request;
import java.time.Instant;
import java.util.Set;
public record GiftPolicyRequest(boolean enabled, Instant opensAt, Instant closesAt, Integer invitationTtlHours,
        String ticketTypeScope, Set<String> allowedTicketTypeIds, Long expectedVersion) {}
