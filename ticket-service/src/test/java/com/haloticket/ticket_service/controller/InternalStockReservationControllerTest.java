package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.service.StockReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The gateway can route to this controller, so the token check is the only gate. */
class InternalStockReservationControllerTest {

    private static final String TOKEN = "test-token-value";
    private static final String BODY = "{\"bookingId\":\"b1\",\"eventId\":\"e1\",\"showtimeId\":null,"
            + "\"expiresAt\":\"" + Instant.now().plusSeconds(900) + "\",\"items\":[{\"ticketTypeId\":\"t1\",\"quantity\":2}]}";

    private StockReservationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StockReservationService.class);
        mvc = build(TOKEN);
    }

    private MockMvc build(String configuredToken) {
        return MockMvcBuilders.standaloneSetup(new InternalStockReservationController(service, configuredToken)).build();
    }

    @Test
    void missingOrWrongTokenIsForbiddenAndNothingIsReserved() throws Exception {
        mvc.perform(post("/api/internal/tickets/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/tickets/stock-reservations").header("X-Internal-Token", "nope")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void blankConfiguredTokenRejectsEveryCallEvenAnEmptyOne() throws Exception {
        MockMvc unconfigured = build("");
        unconfigured.perform(post("/api/internal/tickets/stock-reservations").header("X-Internal-Token", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        unconfigured.perform(post("/api/internal/tickets/stock-reservations/b1/release").header("X-Internal-Token", ""))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void validTokenReserves() throws Exception {
        mvc.perform(post("/api/internal/tickets/stock-reservations").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(true));
        verify(service).reserve(eq("b1"), eq("e1"), any(), any(Instant.class), anyList());
    }

    @Test
    void soldOutIs409WithACodeAndTheAvailableCount() throws Exception {
        doThrow(new StockReservationService.SoldOutException("t1", 1))
                .when(service).reserve(any(), any(), any(), any(), anyList());

        mvc.perform(post("/api/internal/tickets/stock-reservations").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_SOLD_OUT"))
                .andExpect(jsonPath("$.available").value(1));
    }

    @Test
    void releaseNeedsTheTokenToo() throws Exception {
        when(service.release("b1")).thenReturn(1);

        mvc.perform(post("/api/internal/tickets/stock-reservations/b1/release")).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/tickets/stock-reservations/b1/release").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(1));
    }

    private static final String REOPEN = "/api/internal/tickets/stock-reservations/b1/reopen";
    private static final String REOPEN_BODY = "{\"expiresAt\":\"" + Instant.now().plusSeconds(600) + "\"}";

    @Test
    void reopenNeedsTheToken() throws Exception {
        mvc.perform(post(REOPEN).contentType(MediaType.APPLICATION_JSON).content(REOPEN_BODY)).andExpect(status().isForbidden());
        mvc.perform(post(REOPEN).header("X-Internal-Token", "nope").contentType(MediaType.APPLICATION_JSON).content(REOPEN_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void reopenSucceeds() throws Exception {
        mvc.perform(post(REOPEN).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(REOPEN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reopened").value(true));
        verify(service).reopen(eq("b1"), any(Instant.class));
    }

    @Test
    void reopenSoldOutIs409WithTheReserveBody() throws Exception {
        doThrow(new StockReservationService.SoldOutException("t1", 0)).when(service).reopen(any(), any());

        mvc.perform(post(REOPEN).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(REOPEN_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_SOLD_OUT"))
                .andExpect(jsonPath("$.ticketTypeId").value("t1"))
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void reopenOfAConsumedOrUnknownBookingIs409NotReopenable() throws Exception {
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "RESERVATION_NOT_REOPENABLE"))
                .when(service).reopen(any(), any());

        mvc.perform(post(REOPEN).header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(REOPEN_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_REOPENABLE"));
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
