package com.haloticket.ticket_service.controller;

import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketStockReservation;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.StockReservationStatus;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.TicketRepository;
import com.haloticket.ticket_service.repository.TicketStockReservationRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Token, limits and the real SQL (on H2) of the read-only reconciliation endpoints. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
class InternalReconciliationControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    private static final String TOKEN = "test-token-value";
    private static final String H = "X-Internal-Token";

    @Autowired TicketRepository tickets;
    @Autowired TicketTypeRepository types;
    @Autowired TicketStockReservationRepository reservations;

    @AfterEach
    void clean() {
        tickets.deleteAll();
        reservations.deleteAll();
        types.deleteAll();
    }

    private MockMvc mvc(TicketRepository t, TicketTypeRepository tt, String token) {
        return MockMvcBuilders.standaloneSetup(new InternalReconciliationController(t, tt, token)).build();
    }

    private TicketType type(String name, Integer initial, Integer total, int reserved) {
        return types.save(TicketType.builder().eventId("e1").showtimeId("s1").name(name).price(1d)
                .initialQuantity(initial).totalQuantity(total).reservedQuantity(reserved).build());
    }

    private void ticket(TicketType type, String bookingId, String acquisition, TicketStatus status) {
        tickets.save(Ticket.builder().ticketType(type).eventId("e1").ownerId("u").bookingId(bookingId)
                .acquisitionType(acquisition).status(status).issueKey(java.util.UUID.randomUUID().toString()).build());
    }

    private MockMvc real() {
        return mvc(tickets, types, TOKEN);
    }

    @Test
    void aLegacyRowThatOnlyLacksAnInitialNumberIsNotListedButOneWithSoldTicketsIs() throws Exception {
        type("legacy-mock", null, 200, 0); // old seed data: nothing sold, nothing to reconcile
        TicketType sold = type("legacy-sold", null, 99, 0);
        ticket(sold, "b1", "PRIMARY", TicketStatus.VALID);

        var body = real().perform(get("/api/internal/tickets/stock-reconciliation").header(H, TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("legacy-sold").doesNotContain("legacy-mock");
    }

    @Test
    void fixStockSetsRemainingToInitialMinusIssued() throws Exception {
        // the case seen in production: 1000 issued number, one ticket sold, remaining still 1000
        TicketType drifted = type("STANDARD", 1000, 1000, 0);
        ticket(drifted, "b1", "PRIMARY", TicketStatus.VALID);
        ticket(drifted, "b2", "PRIMARY", TicketStatus.REVOKED); // does not count

        real().perform(post("/api/internal/tickets/stock-reconciliation/" + drifted.getId() + "/fix").header(H, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initial").value(1000))
                .andExpect(jsonPath("$.remaining").value(999))
                .andExpect(jsonPath("$.issued").value(1))
                .andExpect(jsonPath("$.diffStockVsIssued").value(0));
        assertThat(types.findById(drifted.getId()).orElseThrow().getTotalQuantity()).isEqualTo(999);
    }

    @Test
    void fixStockOfARowWithoutAnInitialNumberDerivesItFromRemainingPlusIssued() throws Exception {
        TicketType legacy = type("old", null, 98, 0);
        ticket(legacy, "b1", "PRIMARY", TicketStatus.VALID);
        ticket(legacy, "b2", "PRIMARY", TicketStatus.VALID);

        real().perform(post("/api/internal/tickets/stock-reconciliation/" + legacy.getId() + "/fix").header(H, TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.initial").value(100)).andExpect(jsonPath("$.remaining").value(98));
    }

    @Test
    void fixStockRefusesWhatWouldBeWrongAndChangesNothing() throws Exception {
        TicketType tooFew = type("too-few", 1, 1, 0);
        ticket(tooFew, "b1", "PRIMARY", TicketStatus.VALID);
        ticket(tooFew, "b2", "PRIMARY", TicketStatus.VALID);
        TicketType held = type("held", 10, 10, 9);
        ticket(held, "b3", "PRIMARY", TicketStatus.VALID);
        ticket(held, "b4", "PRIMARY", TicketStatus.VALID);
        TicketType unknown = type("unknown", null, null, 0);

        real().perform(post("/api/internal/tickets/stock-reconciliation/" + tooFew.getId() + "/fix").header(H, TOKEN))
                .andExpect(status().isConflict());
        real().perform(post("/api/internal/tickets/stock-reconciliation/" + held.getId() + "/fix").header(H, TOKEN))
                .andExpect(status().isConflict()); // remaining would be 8, below the 9 held by unpaid bookings
        real().perform(post("/api/internal/tickets/stock-reconciliation/" + unknown.getId() + "/fix").header(H, TOKEN))
                .andExpect(status().isUnprocessableEntity());
        real().perform(post("/api/internal/tickets/stock-reconciliation/missing/fix").header(H, TOKEN))
                .andExpect(status().isNotFound());
        assertThat(types.findById(tooFew.getId()).orElseThrow().getTotalQuantity()).isEqualTo(1);
        assertThat(types.findById(held.getId()).orElseThrow().getTotalQuantity()).isEqualTo(10);
    }

    @Test
    void fixStockNeedsTheToken() throws Exception {
        TicketType t = type("x", 10, 10, 0);
        real().perform(post("/api/internal/tickets/stock-reconciliation/" + t.getId() + "/fix")).andExpect(status().isForbidden());
        real().perform(post("/api/internal/tickets/stock-reconciliation/" + t.getId() + "/fix").header(H, "nope")).andExpect(status().isForbidden());
    }

    @Test
    @Transactional // the service that calls these updates is transactional in production
    void anOrganiserEditOfARowWithoutAnInitialNumberKeepsTheTicketsAlreadySold() {
        // before: sync computed sold = 0 for such a row and reset the remaining stock to the new number
        TicketType legacy = type("old", null, 99, 0);
        ticket(legacy, "b1", "PRIMARY", TicketStatus.VALID);

        types.backfillInitialQuantityAtomic(legacy.getId());
        int updated = types.applyIssuedQuantityAtomic(legacy.getId(), 1000, "old", 1d, false, 1, 5, null, java.time.LocalDateTime.now());

        assertThat(updated).isEqualTo(1);
        TicketType after = types.findById(legacy.getId()).orElseThrow();
        assertThat(after.getInitialQuantity()).isEqualTo(1000);
        assertThat(after.getTotalQuantity()).isEqualTo(999); // 1000 - the one ticket sold
    }

    @Test
    void tokenIsRequiredOnBothEndpointsAndNothingIsQueriedWithoutIt() throws Exception {
        TicketRepository t = mock(TicketRepository.class);
        TicketTypeRepository tt = mock(TicketTypeRepository.class);
        MockMvc mvc = mvc(t, tt, TOKEN);

        mvc.perform(post("/api/internal/tickets/issued-counts").contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[\"b\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/tickets/issued-counts").header(H, "nope").contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[\"b\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/internal/tickets/stock-reconciliation")).andExpect(status().isForbidden());
        mvc(t, tt, "").perform(get("/api/internal/tickets/stock-reconciliation").header(H, ""))
                .andExpect(status().isForbidden());
        verifyNoInteractions(t, tt);
    }

    @Test
    void moreThan500BookingIdsIs400AndExactly500IsAccepted() throws Exception {
        TicketRepository t = mock(TicketRepository.class);
        MockMvc mvc = mvc(t, mock(TicketTypeRepository.class), TOKEN);
        String tooMany = "{\"bookingIds\":[" + String.join(",", java.util.Collections.nCopies(501, "\"x\"")) + "]}";
        String max = "{\"bookingIds\":[" + String.join(",", java.util.stream.IntStream.range(0, 500).mapToObj(i -> "\"b" + i + "\"").toList()) + "]}";

        mvc.perform(post("/api/internal/tickets/issued-counts").header(H, TOKEN).contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(t);
        mvc.perform(post("/api/internal/tickets/issued-counts").header(H, TOKEN).contentType(MediaType.APPLICATION_JSON).content(max))
                .andExpect(status().isOk());
    }

    @Test
    void limitDefaultsTo200AndIsClampedToTheRange1To500() throws Exception {
        TicketTypeRepository tt = mock(TicketTypeRepository.class);
        MockMvc mvc = mvc(mock(TicketRepository.class), tt, TOKEN);

        mvc.perform(get("/api/internal/tickets/stock-reconciliation").header(H, TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/internal/tickets/stock-reconciliation?limit=99999").header(H, TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/internal/tickets/stock-reconciliation?limit=0").header(H, TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/api/internal/tickets/stock-reconciliation?limit=37").header(H, TOKEN)).andExpect(status().isOk());

        verify(tt).findStockDiscrepancies(200);
        verify(tt).findStockDiscrepancies(500);
        verify(tt).findStockDiscrepancies(1);
        verify(tt).findStockDiscrepancies(37);
        verify(tt, times(4)).findStockDiscrepancies(anyInt());
    }

    @Test
    void issuedCountsCountOnlyPrimaryNonRevokedTicketsAndListEveryRequestedBooking() throws Exception {
        TicketType ga = type("GA", 100, 100, 0);
        ticket(ga, "b1", "PRIMARY", TicketStatus.VALID);
        ticket(ga, "b1", null, TicketStatus.USED);
        ticket(ga, "b1", "PRIMARY", TicketStatus.REVOKED);
        ticket(ga, "b1", "RESALE", TicketStatus.VALID);
        ticket(ga, "b2", "PRIMARY", TicketStatus.VALID);
        ticket(ga, "other", "PRIMARY", TicketStatus.VALID);

        mvc(tickets, types, TOKEN).perform(post("/api/internal/tickets/issued-counts").header(H, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingIds\":[\"b1\",\"b2\",\"b3\",\"b1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.b1").value(2))
                .andExpect(jsonPath("$.counts.b2").value(1))
                .andExpect(jsonPath("$.counts.b3").value(0))
                .andExpect(jsonPath("$.counts.other").doesNotExist());
    }

    @Test
    void stockReconciliationListsOnlyRowsWithADiscrepancyOrANote() throws Exception {
        TicketType clean = type("CLEAN", 10, 8, 0);
        ticket(clean, "b", "PRIMARY", TicketStatus.VALID);
        ticket(clean, "b", "PRIMARY", TicketStatus.VALID);
        TicketType heldClean = type("HELD-OK", 10, 10, 2);
        reservations.save(TicketStockReservation.builder().bookingId("bh").ticketTypeId(heldClean.getId()).quantity(2)
                .status(StockReservationStatus.HELD).expiresAt(Instant.now().plusSeconds(60)).build());

        TicketType overwritten = type("OVERWRITTEN", 10, 10, 0); // one ticket but stock never dropped
        ticket(overwritten, "b2", "PRIMARY", TicketStatus.VALID);
        TicketType phantomHold = type("PHANTOM-HOLD", 10, 10, 3); // reserved with no HELD reservation rows
        TicketType legacy = type("LEGACY", null, 5, 0);
        ticket(legacy, "bl", "PRIMARY", TicketStatus.VALID); // a legacy row WITH a sold ticket is still listed (a legacy row with none is not)
        type("LEGACY-UNUSED", null, 7, 0);

        String body = mvc(tickets, types, TOKEN).perform(get("/api/internal/tickets/stock-reconciliation?limit=50").header(H, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        Map<String, com.fasterxml.jackson.databind.JsonNode> byName = new java.util.HashMap<>();
        json.forEach(n -> byName.put(n.get("name").asText(), n));
        assertThat(byName).containsOnlyKeys("OVERWRITTEN", "PHANTOM-HOLD", "LEGACY");

        var o = byName.get("OVERWRITTEN");
        assertThat(o.get("ticketTypeId").asText()).isEqualTo(overwritten.getId());
        assertThat(o.get("eventId").asText()).isEqualTo("e1");
        assertThat(o.get("showtimeId").asText()).isEqualTo("s1");
        assertThat(o.get("initial").asInt()).isEqualTo(10);
        assertThat(o.get("remaining").asInt()).isEqualTo(10);
        assertThat(o.get("issued").asInt()).isEqualTo(1);
        assertThat(o.get("diffStockVsIssued").asInt()).isEqualTo(-1);
        assertThat(o.get("diffHeldVsReservations").asInt()).isZero();

        var p = byName.get(phantomHold.getName());
        assertThat(p.get("held").asInt()).isEqualTo(3);
        assertThat(p.get("heldByReservations").asInt()).isZero();
        assertThat(p.get("diffHeldVsReservations").asInt()).isEqualTo(3);

        var l = byName.get(legacy.getName());
        assertThat(l.get("diffStockVsIssued").isNull()).isTrue();
        assertThat(l.get("note").asText()).contains("INITIAL_QUANTITY_NULL");
    }

    @Test
    void stockReconciliationHonoursTheLimit() throws Exception {
        for (int i = 0; i < 5; i++) type("BAD-" + i, 10, 10, 1);

        mvc(tickets, types, TOKEN).perform(get("/api/internal/tickets/stock-reconciliation?limit=2").header(H, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
