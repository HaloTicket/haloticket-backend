package com.haloticket.booking_service.controller;

import com.haloticket.booking_service.config.BookingJwtRoleConverter;
import com.haloticket.booking_service.config.InternalReportSecurityConfig;
import com.haloticket.booking_service.exception.GlobalExceptionHandler;
import com.haloticket.booking_service.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminReconciliationController.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/jwks"
})
@Import({InternalReportSecurityConfig.class, BookingJwtRoleConverter.class, GlobalExceptionHandler.class})
class AdminReconciliationControllerTest {
    private static final String BASE = "/api/bookings/admin/reconciliation";

    @Autowired MockMvc mvc;
    @MockitoBean ReconciliationService service;
    @MockitoBean JwtDecoder jwtDecoder;

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class WebSecurityEnabled {}

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("admin")).thenReturn(token("admin", "ROLE_ADMIN"));
        when(jwtDecoder.decode("customer")).thenReturn(token("customer", "ROLE_CUSTOMER"));
    }

    @Test
    void summaryForAdmin() throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("openExceptions", 2L);
        summary.put("paidWithoutTickets", null);
        summary.put("stockDiscrepancies", 0);
        when(service.summary()).thenReturn(summary);

        mvc.perform(get(BASE + "/summary").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openExceptions").value(2))
                .andExpect(jsonPath("$.paidWithoutTickets").doesNotExist())
                .andExpect(jsonPath("$.stockDiscrepancies").value(0));
    }

    @Test
    void everyEndpointIsForbiddenForNonAdminsAndUnauthorizedForAnonymous() throws Exception {
        for (String path : List.of("/summary", "/exceptions", "/paid-without-tickets", "/stock-discrepancies")) {
            mvc.perform(get(BASE + path).header("Authorization", "Bearer customer")).andExpect(status().isForbidden());
            mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(BASE + "/exceptions/e-1/resolve").header("Authorization", "Bearer customer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"valid note\"}")).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/exceptions/e-1/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"valid note\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void exceptionsListPassesFilterAndPaging() throws Exception {
        when(service.exceptions(any(), anyInt(), anyInt())).thenReturn(new ReconciliationService.ExceptionPage(List.of(), 0, 1, 5));

        mvc.perform(get(BASE + "/exceptions").param("status", "OPEN").param("page", "1").param("size", "5")
                        .header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isArray());

        verify(service).exceptions(com.haloticket.booking_service.enums.PaymentExceptionStatus.OPEN, 1, 5);
    }

    @Test
    void exceptionsListRejectsBadStatusAndPaging() throws Exception {
        mvc.perform(get(BASE + "/exceptions").param("status", "NOPE").header("Authorization", "Bearer admin"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        mvc.perform(get(BASE + "/exceptions").param("size", "1000").header("Authorization", "Bearer admin"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(get(BASE + "/exceptions").param("page", "-1").header("Authorization", "Bearer admin"))
                .andExpect(status().isUnprocessableEntity());
        verify(service, never()).exceptions(any(), anyInt(), anyInt());
    }

    @Test
    void resolveUsesTheAdminsSubjectNotAnEmail() throws Exception {
        when(service.resolve(anyString(), anyString(), anyBoolean(), anyString())).thenReturn(
                new ReconciliationService.ExceptionItem("e-1", "b-1", "HALO-1", "pay-1", "LATE_PAYMENT_NO_STOCK", 1000d, "VNPAY",
                        "REFUNDED_MANUALLY", "d", null, null, "test-user", "refunded"));

        mvc.perform(post(BASE + "/exceptions/e-1/resolve").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"refunded\",\"refundedManually\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED_MANUALLY"));

        verify(service).resolve("e-1", "refunded", true, "test-user");
    }

    @Test
    void resolveValidationErrorsComeBackAs422() throws Exception {
        when(service.resolve(anyString(), any(), anyBoolean(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESOLUTION_NOTE"));

        mvc.perform(post(BASE + "/exceptions/e-1/resolve").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RESOLUTION_NOTE"));
    }

    @Test
    void sourceFailureIsA502WithItsCode() throws Exception {
        when(service.paidWithoutTickets()).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RECONCILIATION_SOURCE_UNAVAILABLE"));
        when(service.stockDiscrepancies()).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RECONCILIATION_SOURCE_UNAVAILABLE"));

        mvc.perform(get(BASE + "/paid-without-tickets").header("Authorization", "Bearer admin"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("RECONCILIATION_SOURCE_UNAVAILABLE"));
        mvc.perform(get(BASE + "/stock-discrepancies").header("Authorization", "Bearer admin"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("RECONCILIATION_SOURCE_UNAVAILABLE"));
    }

    @Test
    void paidWithoutTicketsAndStockPayloads() throws Exception {
        when(service.paidWithoutTickets()).thenReturn(new ReconciliationService.PaidWithoutTickets(List.of(
                new ReconciliationService.PaidWithoutTicketsItem("b-1", "HALO-1", null, 2, 1, 30)), 1, false));
        when(service.stockDiscrepancies()).thenReturn(new ReconciliationService.StockDiscrepancies(
                List.of(Map.of("ticketTypeId", "t1")), 1));

        mvc.perform(get(BASE + "/paid-without-tickets").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].bookingCode").value("HALO-1"))
                .andExpect(jsonPath("$.items[0].expectedTickets").value(2))
                .andExpect(jsonPath("$.items[0].issuedTickets").value(1))
                .andExpect(jsonPath("$.items[0].minutesSincePaid").value(30));
        mvc.perform(get(BASE + "/stock-discrepancies").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ticketTypeId").value("t1"));
    }

    private static Jwt token(String value, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(value).header("alg", "none").issuer("https://issuer.example")
                .subject("test-user").claim("email", "someone@test.local").issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of(role))).build();
    }
}
