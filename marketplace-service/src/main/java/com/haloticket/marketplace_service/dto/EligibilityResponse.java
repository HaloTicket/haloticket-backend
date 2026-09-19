package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.client.TicketServiceClient;
public record EligibilityResponse(TicketServiceClient.ResaleEligibilityResponse data) {}
