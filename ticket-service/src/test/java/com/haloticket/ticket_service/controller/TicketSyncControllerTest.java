package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.authz.EventAccessClient;
import com.haloticket.ticket_service.exception.QuantityBelowSoldAndHeldException;
import com.haloticket.ticket_service.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketSyncControllerTest {

    @Test
    void quantityBelowSoldAndHeldIs422WithTheCodeAndTheMinimum() throws Exception {
        TicketService tickets = mock(TicketService.class);
        EventAccessClient access = mock(EventAccessClient.class);
        when(access.isInternal(anyString())).thenReturn(true);
        when(tickets.syncTicketTypesForEvent(anyString(), any(), anyList()))
                .thenThrow(new QuantityBelowSoldAndHeldException("tt-1", "VIP", 3, 5));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TicketController(tickets, access)).build();

        mvc.perform(post("/api/tickets/types/sync/e1?showtimeId=s1").header("X-Internal-Token", "x")
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"name\":\"VIP\",\"initialQuantity\":7}]"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_BELOW_SOLD_AND_HELD"))
                .andExpect(jsonPath("$.ticketTypeId").value("tt-1"))
                .andExpect(jsonPath("$.name").value("VIP"))
                .andExpect(jsonPath("$.sold").value(3))
                .andExpect(jsonPath("$.held").value(5))
                .andExpect(jsonPath("$.minAllowed").value(8));
    }
}
