package com.haloticket.marketplace_service.dto;
import com.haloticket.marketplace_service.client.TicketServiceClient;
/** Phản hồi kiểm tra điều kiện bán lại, bọc kết quả từ {@code ticket-service}. */
public record EligibilityResponse(TicketServiceClient.ResaleEligibilityResponse data) {}
