package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.enums.PaymentExceptionStatus;
import com.haloticket.booking_service.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

/** Admin order reconciliation (ROLE_ADMIN only). The rows carry the customer name, e-mail and phone so a person can contact them; never log them. */
@RestController
@RequestMapping("/api/bookings/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private static final int MAX_PAGE_SIZE = 100;

    public record ResolveRequest(String note, Boolean refundedManually) {}

    private final ReconciliationService reconciliationService;

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return reconciliationService.summary();
    }

    @GetMapping("/exceptions")
    public ReconciliationService.ExceptionPage exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PaymentExceptionStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = PaymentExceptionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS");
            }
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAGE");
        }
        return reconciliationService.exceptions(filter, page, size);
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ReconciliationService.ExceptionItem resolve(@PathVariable String id, @RequestBody ResolveRequest body,
                                                      @AuthenticationPrincipal Jwt jwt) {
        String admin = jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank() ? jwt.getSubject() : "unknown";
        return reconciliationService.resolve(id, body == null ? null : body.note(),
                body != null && Boolean.TRUE.equals(body.refundedManually()), admin);
    }

    @GetMapping("/paid-without-tickets")
    public ReconciliationService.PaidWithoutTickets paidWithoutTickets() {
        return reconciliationService.paidWithoutTickets();
    }

    /** "Đồng bộ lại kho" for one row: remaining := initial - issued. Refusals (404/409/422) are passed on with nothing changed. */
    @PostMapping("/stock/{ticketTypeId}/fix")
    public Map<String, Object> fixStock(@PathVariable String ticketTypeId) {
        return reconciliationService.fixStock(ticketTypeId);
    }

    @GetMapping("/stock-discrepancies")
    public ReconciliationService.StockDiscrepancies stockDiscrepancies() {
        return reconciliationService.stockDiscrepancies();
    }
}
