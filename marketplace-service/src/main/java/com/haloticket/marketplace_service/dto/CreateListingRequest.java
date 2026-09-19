package com.haloticket.marketplace_service.dto;
import java.math.BigDecimal;
public record CreateListingRequest(String ticketId, BigDecimal price, Long policyVersion, String termsVersion,
                                   Boolean allowRetail) {}
