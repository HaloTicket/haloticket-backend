package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.dto.request.TicketTypeCreateRequest;
import com.haloticket.ticket_service.dto.response.TicketTypeResponse;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.TicketTypeStatus;
import com.haloticket.ticket_service.exception.QuantityBelowSoldAndHeldException;
import com.haloticket.ticket_service.repository.TicketStockReservationRepository;
import com.haloticket.ticket_service.repository.TicketTypeRepository;
import com.haloticket.ticket_service.service.impl.TicketServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Real transactions on H2 so the conditional UPDATE, rollback and concurrency behave as they do in production. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
@Import(TicketServiceImpl.class)
class TicketTypeSyncTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    private static final String EVENT = "event-1";

    @Autowired TicketServiceImpl service;
    @Autowired TicketTypeRepository types;
    @Autowired TicketStockReservationRepository reservations;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void clean() {
        reservations.deleteAll();
        types.deleteAll();
    }

    private static TicketTypeCreateRequest req(String id, String name, Integer initial) {
        TicketTypeCreateRequest r = new TicketTypeCreateRequest();
        r.setId(id);
        r.setName(name);
        r.setPrice(100000.0);
        r.setInitialQuantity(initial);
        return r;
    }

    private TicketType only(String showtimeId, String name) {
        return types.findByEventId(EVENT).stream()
                .filter(t -> java.util.Objects.equals(t.getShowtimeId(), showtimeId) && t.getName().equalsIgnoreCase(name))
                .findFirst().orElseThrow();
    }

    private void sell(TicketType t, int n) {
        new TransactionTemplate(txManager).executeWithoutResult(st -> {
            for (int i = 0; i < n; i++) types.decrementTotalQuantityAtomic(t.getId());
        });
    }

    @Test
    void sellThenResaveSameNumbersKeepsRemainingStock() {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 1000)));
        sell(only("s1", "GA"), 3);

        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(only("s1", "GA").getId(), "GA", 1000)));

        TicketType after = only("s1", "GA");
        assertThat(after.getInitialQuantity()).isEqualTo(1000);
        assertThat(after.getTotalQuantity()).isEqualTo(997);
    }

    @Test
    void raisingTheIssuedNumberRaisesRemainingStock() {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 1000)));
        sell(only("s1", "GA"), 3);

        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 1500)));

        assertThat(only("s1", "GA").getInitialQuantity()).isEqualTo(1500);
        assertThat(only("s1", "GA").getTotalQuantity()).isEqualTo(1497);
    }

    @Test
    void loweringBelowSoldPlusHeldIs422DataAndWholeSyncRolledBack() {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 10), req(null, "VIP", 10)));
        TicketType ga = only("s1", "GA");
        sell(ga, 3);
        Integer held = new TransactionTemplate(txManager).execute(st -> types.reserveAtomic(ga.getId(), 5));
        assertThat((int) held).isEqualTo(1);

        TicketTypeCreateRequest okChange = req(null, "VIP", 10);
        okChange.setPrice(999.0);
        // GA first would fail first; put VIP (valid) before GA so a partial apply would be visible
        assertThatThrownBy(() -> service.syncTicketTypesForEvent(EVENT, "s1", List.of(okChange, req(null, "GA", 7))))
                .isInstanceOfSatisfying(QuantityBelowSoldAndHeldException.class, e -> {
                    assertThat(e.getTicketTypeId()).isEqualTo(ga.getId());
                    assertThat(e.getSold()).isEqualTo(3);
                    assertThat(e.getHeld()).isEqualTo(5);
                    assertThat(e.getMinAllowed()).isEqualTo(8);
                });

        TicketType gaAfter = only("s1", "GA");
        assertThat(gaAfter.getInitialQuantity()).isEqualTo(10);
        assertThat(gaAfter.getTotalQuantity()).isEqualTo(7);
        assertThat(only("s1", "VIP").getPrice()).isEqualTo(100000.0);

        // exactly sold + held is accepted
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 8)));
        assertThat(only("s1", "GA").getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void hiddenStaysHiddenAndNewNumbersStillApply() {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 10)));
        TicketType ga = only("s1", "GA");
        ga.setTicketTypeStatus(TicketTypeStatus.HIDDEN);
        types.save(ga);

        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 20)));

        assertThat(only("s1", "GA").getTicketTypeStatus()).isEqualTo(TicketTypeStatus.HIDDEN);
        assertThat(only("s1", "GA").getInitialQuantity()).isEqualTo(20);
    }

    @Test
    void savingTwiceCreatesNoDuplicateRows() {
        List<TicketTypeCreateRequest> body = List.of(req("tier_1", "GA", 10), req("tier_2", "VIP", 5));
        service.syncTicketTypesForEvent(EVENT, "s1", body);
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req("tier_1", " ga ", 10), req("tier_2", "vip", 5)));

        assertThat(types.findByEventId(EVENT)).hasSize(2);
    }

    @Test
    void twoShowtimesByThreeTiersGiveSixRowsAndNoMasterRows() {
        List<String> names = List.of("GA", "VIP", "SVIP");
        for (String showtime : List.of("s1", "s2")) {
            service.syncTicketTypesForEvent(EVENT, showtime, names.stream().map(n -> req(null, n, 100)).toList());
        }
        // an event-level sync after the showtimes exist must not create master rows nor duplicate anything
        service.syncTicketTypesForEvent(EVENT, null, names.stream().map(n -> req(null, n, 100)).toList());

        List<TicketType> rows = types.findByEventId(EVENT);
        assertThat(rows).hasSize(6);
        assertThat(rows).allMatch(t -> t.getShowtimeId() != null);
    }

    @Test
    void listsReturnPerShowtimeRowsAndIgnoreLegacyMasterRows() {
        for (String showtime : List.of("s1", "s2")) {
            service.syncTicketTypesForEvent(EVENT, showtime, List.of(req(null, "GA", 100), req(null, "VIP", 50)));
        }
        types.save(TicketType.builder().eventId(EVENT).name("GA").price(1.0).totalQuantity(1000).initialQuantity(1000).build());

        List<TicketTypeResponse> byEvent = service.getTicketTypesByEventId(EVENT);
        List<TicketTypeResponse> all = service.getAllTicketTypesByEventId(EVENT);

        assertThat(byEvent).hasSize(4).allMatch(r -> r.getShowtimeId() != null);
        assertThat(all).hasSize(4).allMatch(r -> r.getShowtimeId() != null);
        assertThat(service.getTicketTypesByShowtimeId("s2")).hasSize(2);
    }

    @Test
    void eventWithoutShowtimesKeepsItsSingleMasterSet() {
        service.syncTicketTypesForEvent(EVENT, null, List.of(req(null, "GA", 100)));
        service.syncTicketTypesForEvent(EVENT, null, List.of(req(null, "GA", 100)));

        assertThat(types.findByEventId(EVENT)).hasSize(1);
        assertThat(service.getTicketTypesByEventId(EVENT)).hasSize(1);
    }

    @Test
    void renameByIdKeepsTheRowAndNeverGuessesByElimination() {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 10), req(null, "VIP", 10)));
        String gaId = only("s1", "GA").getId();

        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(gaId, "General", 10), req(null, "VIP", 10)));
        assertThat(types.findByEventId(EVENT)).hasSize(2);
        assertThat(types.findById(gaId).orElseThrow().getName()).isEqualTo("General");

        // a request with a new name and no id is a new tier, not a guessed rename of the one that disappeared
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "Brand New", 10), req(null, "VIP", 10)));
        assertThat(types.findByEventId(EVENT)).extracting(TicketType::getName).containsExactlyInAnyOrder("Brand New", "VIP");
    }

    @Test
    void legacyRowWithNullInitialGetsBothNumbersSet() {
        TicketType legacy = types.save(TicketType.builder().eventId(EVENT).showtimeId("s1").name("GA").price(1.0).totalQuantity(500).build());

        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(legacy.getId(), "GA", 800)));

        TicketType after = types.findById(legacy.getId()).orElseThrow();
        assertThat(after.getInitialQuantity()).isEqualTo(800);
        assertThat(after.getTotalQuantity()).isEqualTo(800);
    }

    @Test
    void newRowStartsWithTotalEqualInitialAndFallsBackToLegacyField() {
        TicketTypeCreateRequest legacyShape = req(null, "Old", null);
        legacyShape.setTotalQuantity(40);
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "New", 25), legacyShape));

        assertThat(only("s1", "New").getTotalQuantity()).isEqualTo(25);
        assertThat(only("s1", "New").getInitialQuantity()).isEqualTo(25);
        assertThat(only("s1", "Old").getInitialQuantity()).isEqualTo(40);
    }

    @Test
    void parallelReservesAndQuantityEditsNeverOverbookOrGoNegative() throws Exception {
        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(null, "GA", 100)));
        String id = only("s1", "GA").getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        int workers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                if (n % 3 == 0) {
                    try {
                        service.syncTicketTypesForEvent(EVENT, "s1", List.of(req(id, "GA", 40 + n)));
                    } catch (QuantityBelowSoldAndHeldException ignored) {
                        // refused because the holds already exceed the new number
                    }
                } else {
                    for (int k = 0; k < 4; k++) {
                        Integer updated = tx.execute(status -> types.reserveAtomic(id, 5));
                        if (updated != null && updated == 1) reserved.addAndGet(5);
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        TicketType after = types.findById(id).orElseThrow();
        assertThat(after.getReservedQuantity()).isEqualTo(reserved.get());
        assertThat(after.getTotalQuantity()).isGreaterThanOrEqualTo(after.getReservedQuantity());
        assertThat(after.getTotalQuantity()).isEqualTo(after.getInitialQuantity()); // nothing sold
        assertThat(after.getTotalQuantity()).isGreaterThanOrEqualTo(0);
    }
}
