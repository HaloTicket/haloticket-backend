package com.haloticket.payment_service.service;

import com.haloticket.payment_service.dto.request.PayoutCreateRequest;
import com.haloticket.payment_service.dto.response.PayoutResponse;

import java.util.List;
import com.haloticket.payment_service.dto.response.EventFinanceSource;
import com.haloticket.payment_service.dto.response.OrganizerFinanceResponse;
import com.haloticket.payment_service.dto.response.PayoutQuoteResponse;
import com.haloticket.payment_service.dto.response.OrganizerCashFlowResponse;
import java.time.LocalDate;

public interface PayoutService {
    PayoutResponse requestPayout(String organizerId, String authorization, PayoutCreateRequest request, String idempotencyKey);
    PayoutResponse approvePayout(String payoutId);
    PayoutResponse disputePayout(String payoutId, String reason);
    PayoutResponse reopenPayout(String payoutId);
    PayoutResponse rejectPayout(String payoutId, String reason);
    List<PayoutResponse> getPendingPayouts();
    List<PayoutResponse> getAllAdminPayouts();
    List<PayoutResponse> getOrganizerPayouts(String organizerId);
    OrganizerFinanceResponse getOrganizerFinance(String organizerId, String authorization);
    OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to);
    OrganizerCashFlowResponse getOrganizerAnalytics(String organizerId, String authorization, LocalDate from, LocalDate to, String eventId);
    PayoutQuoteResponse quotePayout(String organizerId, String authorization, String eventId, String type, Long requestedAmount);
    PayoutResponse getOrganizerPayout(String organizerId, String payoutId);
    PayoutResponse getByIdempotencyKey(String organizerId, String key);
    PayoutResponse cancelPayout(String organizerId, String payoutId);
    PayoutResponse startDisbursement(String payoutId);
    PayoutResponse completeDisbursement(String payoutId, String reference, java.time.LocalDateTime transferredAt, String receiptUrl);
    String resolveOrganizerId(String authorization);
    FinanceSource getFinanceSource(String eventId, String authorization);
    record FinanceSource(long grossCollected, long refunds, long paidBookingAmount, long pendingRefunds) {}
}
