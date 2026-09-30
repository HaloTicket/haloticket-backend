package com.haloticket.event_service.dto.response;
import com.haloticket.event_service.entity.GiftPolicy;
import java.time.Instant;
import java.util.Set;
public record GiftPolicyResponse(String eventId, boolean enabled, Instant opensAt, Instant closesAt,
        int invitationTtlHours, String ticketTypeScope, Set<String> allowedTicketTypeIds, Long version) {
    public static GiftPolicyResponse from(GiftPolicy p) { return new GiftPolicyResponse(p.getEventId(), p.isEnabled(), p.getOpensAt(), p.getClosesAt(), p.getInvitationTtlHours(), p.getTicketTypeScope(), p.getAllowedTicketTypeIds(), p.getVersion()); }
}
