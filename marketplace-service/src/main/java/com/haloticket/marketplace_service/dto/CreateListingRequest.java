package com.haloticket.marketplace_service.dto;
import java.math.BigDecimal;
import java.util.List;
public record CreateListingRequest(String ticketId, BigDecimal price, Long policyVersion, String termsVersion,
                                   Boolean allowRetail, List<String> ticketIds) {
    public CreateListingRequest(String ticketId, BigDecimal price, Long policyVersion, String termsVersion, Boolean allowRetail) {
        this(ticketId, price, policyVersion, termsVersion, allowRetail, null);
    }
}
