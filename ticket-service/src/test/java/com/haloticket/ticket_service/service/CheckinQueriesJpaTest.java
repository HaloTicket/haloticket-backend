package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.config.CheckinWindowProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.CheckinPageDTO;
import com.haloticket.ticket_service.dto.response.CheckinSummaryDTO;
import com.haloticket.ticket_service.dto.response.CheckinTicketLookupDTO;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.entity.Seat;
import com.haloticket.ticket_service.entity.SeatLayout;
import com.haloticket.ticket_service.entity.SeatRow;
import com.haloticket.ticket_service.entity.SeatZone;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.enums.SeatType;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import com.haloticket.ticket_service.repository.CheckinRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The door-staff queries (summary, search, paged list), the reasons a ticket is refused and the seat fields, against a real
 * JPA stack: the JPQL (COALESCE of the two showtime columns, the entity graph, the count query) only runs for real here.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
@Import({CheckinService.class, CheckinAttemptRecorder.class, CheckinQueriesJpaTest.Beans.class})
class CheckinQueriesJpaTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    @TestConfiguration
    static class Beans {
        @Bean
        CheckinWindowProperties checkinWindowProperties() {
            CheckinWindowProperties p = new CheckinWindowProperties();
            p.setEnabled(false);
            return p;
        }

        @Bean
        EventScheduleService eventScheduleService() {
            return mock(EventScheduleService.class);
        }
    }

    private static final String EVENT = "event-1";
    private static final String OTHER_EVENT = "event-2";
    private static final String SHOW_1 = "showtime-1";
    private static final String SHOW_2 = "showtime-2";

    @Autowired CheckinService service;
    @Autowired TicketRepository tickets;
    @Autowired CheckinRepository checkins;
    @Autowired CheckInAttemptRepository attempts;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    private TransactionTemplate tx;
    private TicketType general;
    private TicketType vip;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        general = persist(TicketType.builder().eventId(EVENT).showtimeId(SHOW_1).name("General").price(100000d)
                .totalQuantity(100).initialQuantity(100).build());
        vip = persist(TicketType.builder().eventId(EVENT).showtimeId(SHOW_2).name("VIP Night").price(900000d)
                .totalQuantity(100).initialQuantity(100).build());
    }

    @AfterEach
    void clean() {
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from CheckInAttempt").executeUpdate();
            em.createQuery("delete from Checkin").executeUpdate();
            em.createQuery("delete from Ticket").executeUpdate();
            em.createQuery("delete from Seat").executeUpdate();
            em.createQuery("delete from SeatRow").executeUpdate();
            em.createQuery("delete from SeatZone").executeUpdate();
            em.createQuery("delete from SeatLayout").executeUpdate();
            em.createQuery("delete from TicketType").executeUpdate();
        });
    }

    private <T> T persist(T entity) {
        return tx.execute(s -> {
            em.persist(entity);
            return entity;
        });
    }

    /** A ticket whose own showtime is empty takes the showtime of its type, so some tests leave it null on purpose. */
    private Ticket ticket(String qr, TicketType type, String showtimeId, TicketStatus status, String booking) {
        return persist(Ticket.builder().ticketType(type).eventId(EVENT).showtimeId(showtimeId).ownerId("owner-12345678")
                .bookingId(booking).qrCodeHash(qr).issueKey("issue-" + qr).status(status).build());
    }

    private Ticket seated(String qr, String zoneName, String rowLabel, String seatNumber) {
        return tx.execute(s -> {
            SeatLayout layout = SeatLayout.builder().eventId(EVENT).status(SeatLayoutStatus.PUBLISHED).build();
            em.persist(layout);
            SeatZone zone = SeatZone.builder().seatLayout(layout).name(zoneName).build();
            em.persist(zone);
            SeatRow row = SeatRow.builder().seatZone(zone).label(rowLabel).build();
            em.persist(row);
            Seat seat = Seat.builder().seatRow(row).seatNumber(seatNumber).seatType(SeatType.REGULAR).seatStatus(SeatStatus.SOLD).build();
            em.persist(seat);
            Ticket t = Ticket.builder().ticketType(vip).seat(seat).eventId(EVENT).showtimeId(SHOW_2).ownerId("owner-12345678")
                    .bookingId("order-seat").qrCodeHash(qr).issueKey("issue-" + qr).status(TicketStatus.VALID).build();
            em.persist(t);
            return t;
        });
    }

    private static CheckinRequest scan(String qr) {
        return CheckinRequest.builder().qrCodeHash(qr).eventId(EVENT).staffAssignmentId("staff-1").deviceInfo("web").build();
    }

    // --- why a ticket is refused ------------------------------------------------------

    @Test
    void aRevokedTicketIsRefusedWithItsOwnCodeButTheAttemptStaysTicketLocked() {
        ticket("qr-revoked", general, SHOW_1, TicketStatus.REVOKED, "order-1");

        TicketCheckinDTO dto = service.processCheckin(scan("qr-revoked"));

        assertThat(dto.getStatus()).isEqualTo("INVALID");
        assertThat(dto.getCode()).isEqualTo("TICKET_REVOKED");
        assertThat(checkins.findAll()).isEmpty();
        // checkin_attempts.result has a CHECK constraint: the stored value is still one it knows
        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult).containsExactly(CheckInAttemptResult.TICKET_LOCKED);
    }

    @Test
    void aTransferredTicketIsRefusedWithItsOwnCode() {
        ticket("qr-moved", general, SHOW_1, TicketStatus.TRANSFERED, "order-1");

        assertThat(service.processCheckin(scan("qr-moved")).getCode()).isEqualTo("TICKET_TRANSFERRED");
    }

    @Test
    void lockedTicketsKeepTheLockedCode() {
        ticket("qr-resale", general, SHOW_1, TicketStatus.LOCKED_FOR_RESALE, "order-1");
        ticket("qr-transfer", general, SHOW_1, TicketStatus.LOCKED_FOR_TRANSFER, "order-1");

        assertThat(service.processCheckin(scan("qr-resale")).getCode()).isEqualTo("TICKET_LOCKED");
        assertThat(service.processCheckin(scan("qr-transfer")).getCode()).isEqualTo("TICKET_LOCKED");
    }

    // --- seat as separate fields ------------------------------------------------------

    @Test
    void theResponseSplitsTheSeatIntoKindZoneRowAndNumberAndKeepsTheSentence() {
        seated("qr-seat", "VIP", "B", "7");
        ticket("qr-ga", general, SHOW_1, TicketStatus.VALID, "order-ga");

        TicketCheckinDTO seat = service.processCheckin(scan("qr-seat"));
        TicketCheckinDTO ga = service.processCheckin(scan("qr-ga"));

        assertThat(seat.getSeatName()).isEqualTo("Khu VIP - Hàng B - Ghế 7");
        assertThat(seat.getTicketKind()).isEqualTo("SEATED");
        assertThat(seat.getZone()).isEqualTo("VIP");
        assertThat(seat.getRow()).isEqualTo("B");
        assertThat(seat.getSeat()).isEqualTo("7");
        assertThat(ga.getTicketKind()).isEqualTo("GENERAL");
        assertThat(ga.getZone()).isNull();
        assertThat(ga.getSeatName()).startsWith("Số thứ tự vé: ");
    }

    @Test
    void aStandingSlotHasNoRow() {
        seated("qr-stand", "Standing A", "STANDING", "31");

        TicketCheckinDTO dto = service.processCheckin(scan("qr-stand"));

        assertThat(dto.getTicketKind()).isEqualTo("STANDING");
        assertThat(dto.getZone()).isEqualTo("Standing A");
        assertThat(dto.getRow()).isNull();
        assertThat(dto.getSeat()).isEqualTo("31");
    }

    // --- summary ----------------------------------------------------------------------

    @Test
    void theSummaryCountsTheTicketsOfTheDoorPerShowtimeAndPerTypeAndLeavesRevokedAndTransferredOut() {
        ticket("s1", general, SHOW_1, TicketStatus.VALID, "o1");
        ticket("s2", general, null, TicketStatus.VALID, "o1");          // no showtime of its own: it takes the type's showtime-1
        ticket("s3", general, SHOW_1, TicketStatus.VALID, "o2");
        ticket("s4", vip, SHOW_2, TicketStatus.VALID, "o3");
        ticket("s5", vip, SHOW_2, TicketStatus.LOCKED_FOR_RESALE, "o3");
        ticket("s6", vip, SHOW_2, TicketStatus.REVOKED, "o3");
        ticket("s7", vip, SHOW_2, TicketStatus.TRANSFERED, "o3");
        service.processCheckin(scan("s1"));
        service.processCheckin(scan("s4"));

        CheckinSummaryDTO summary = service.getSummary(EVENT);

        assertThat(summary.eventId()).isEqualTo(EVENT);
        assertThat(summary.total()).isEqualTo(5);      // s1 s2 s3 s4 s5
        assertThat(summary.checkedIn()).isEqualTo(2);  // s1 s4
        assertThat(summary.byShowtime()).extracting(CheckinSummaryDTO.Group::id, CheckinSummaryDTO.Group::total, CheckinSummaryDTO.Group::checkedIn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(SHOW_1, 3L, 1L),
                        org.assertj.core.groups.Tuple.tuple(SHOW_2, 2L, 1L));
        assertThat(summary.byTicketType()).extracting(CheckinSummaryDTO.Group::name, CheckinSummaryDTO.Group::total, CheckinSummaryDTO.Group::checkedIn)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("General", 3L, 1L),
                        org.assertj.core.groups.Tuple.tuple("VIP Night", 2L, 1L));
    }

    @Test
    void theSummaryOfAnEventWithoutTicketsIsZero() {
        CheckinSummaryDTO summary = service.getSummary("no-tickets");

        assertThat(summary.total()).isZero();
        assertThat(summary.checkedIn()).isZero();
        assertThat(summary.byShowtime()).isEmpty();
        assertThat(summary.byTicketType()).isEmpty();
    }

    // --- search -----------------------------------------------------------------------

    @Test
    void searchFindsATicketByItsShortCodeItsOrderOrItsTypeNameAndMasksTheOwner() {
        Ticket t = ticket("qr-find", vip, SHOW_2, TicketStatus.VALID, "order-ABC-777");
        ticket("qr-other", general, SHOW_1, TicketStatus.VALID, "order-zzz");
        String shortCode = t.getId().split("-")[0];

        List<CheckinTicketLookupDTO> byCode = service.search(EVENT, shortCode.toUpperCase(), null, 20, true);
        List<CheckinTicketLookupDTO> byOrder = service.search(EVENT, "abc-777", null, 20, true);
        List<CheckinTicketLookupDTO> byType = service.search(EVENT, "vip night", null, 20, true);

        assertThat(byCode).extracting(CheckinTicketLookupDTO::ticketId).contains(t.getId());
        assertThat(byOrder).extracting(CheckinTicketLookupDTO::ticketId).containsExactly(t.getId());
        assertThat(byType).extracting(CheckinTicketLookupDTO::ticketId).containsExactly(t.getId());
        CheckinTicketLookupDTO found = byOrder.get(0);
        assertThat(found.ownerMasked()).isEqualTo("owne***").doesNotContain("12345678");
        assertThat(found.bookingId()).isEqualTo("order-ABC-777");
        assertThat(found.ticketTypeName()).isEqualTo("VIP Night");
        assertThat(found.checkedIn()).isFalse();
        assertThat(found.shortCode()).isEqualTo(shortCode.toUpperCase());
    }

    @Test
    void searchGivesTheHashOnlyToAScannerAndOnlyForATicketThatCanStillEnter() {
        ticket("qr-valid", general, SHOW_1, TicketStatus.VALID, "order-hash");
        ticket("qr-used", general, SHOW_1, TicketStatus.VALID, "order-hash");
        service.processCheckin(scan("qr-used"));

        List<CheckinTicketLookupDTO> forScanner = service.search(EVENT, "order-hash", null, 20, true);
        List<CheckinTicketLookupDTO> forViewer = service.search(EVENT, "order-hash", null, 20, false);

        assertThat(forScanner).hasSize(2);
        assertThat(forScanner).filteredOn(r -> !r.checkedIn()).extracting(CheckinTicketLookupDTO::qrCodeHash).containsExactly("qr-valid");
        assertThat(forScanner).filteredOn(CheckinTicketLookupDTO::checkedIn).extracting(CheckinTicketLookupDTO::qrCodeHash).containsOnlyNulls();
        assertThat(forScanner).filteredOn(CheckinTicketLookupDTO::checkedIn).extracting(CheckinTicketLookupDTO::checkedInAt).doesNotContainNull();
        assertThat(forViewer).extracting(CheckinTicketLookupDTO::qrCodeHash).containsOnlyNulls();
    }

    @Test
    void searchHidesRevokedTicketsOtherEventsAndTooShortText() {
        ticket("qr-gone", general, SHOW_1, TicketStatus.REVOKED, "order-secret");
        ticket("qr-here", general, SHOW_1, TicketStatus.VALID, "order-secret-2");
        tx.executeWithoutResult(s -> em.persist(Ticket.builder().ticketType(general).eventId(OTHER_EVENT).showtimeId(SHOW_1).ownerId("x-owner-1")
                .bookingId("order-secret-3").qrCodeHash("qr-elsewhere").issueKey("issue-elsewhere").status(TicketStatus.VALID).build()));

        List<CheckinTicketLookupDTO> found = service.search(EVENT, "order-secret", null, 20, true);

        assertThat(found).extracting(CheckinTicketLookupDTO::bookingId).containsExactly("order-secret-2");
        assertThat(service.search(EVENT, "o", null, 20, true)).isEmpty();
        assertThat(service.search(EVENT, "  ", null, 20, true)).isEmpty();
        assertThat(service.search(EVENT, null, null, 20, true)).isEmpty();
        assertThat(service.search(EVENT, "%%", null, 20, true)).isEmpty();   // wildcard characters are not search text
    }

    @Test
    void searchCanBeNarrowedToOneShowtimeAndIsCapped() {
        ticket("qr-a", general, SHOW_1, TicketStatus.VALID, "order-cap");
        ticket("qr-b", vip, SHOW_2, TicketStatus.VALID, "order-cap");

        assertThat(service.search(EVENT, "order-cap", SHOW_1, 20, true)).extracting(CheckinTicketLookupDTO::showtimeId).containsOnly(SHOW_1);
        assertThat(service.search(EVENT, "order-cap", null, 20, true)).hasSize(2);
        assertThat(service.search(EVENT, "order-cap", null, 1, true)).hasSize(1);
        assertThat(service.search(EVENT, "order-cap", null, 0, true)).hasSize(1);   // a size below one is raised to one
    }

    // --- paged list -------------------------------------------------------------------

    @Test
    void thePageIsNewestFirstHasTheTotalAndCanBeFilteredByShowtime() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            ticket("p" + i, i <= 3 ? general : vip, i <= 3 ? SHOW_1 : SHOW_2, TicketStatus.VALID, "o" + i);
        }
        for (int i = 1; i <= 5; i++) {
            service.processCheckin(scan("p" + i));
            Thread.sleep(5);   // distinct check-in times, so "newest first" is not a tie
        }

        CheckinPageDTO first = service.getCheckinPage(EVENT, null, 0, 2);
        CheckinPageDTO last = service.getCheckinPage(EVENT, null, 2, 2);
        CheckinPageDTO onlyShow2 = service.getCheckinPage(EVENT, SHOW_2, 0, 10);

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.content()).hasSize(2);
        assertThat(first.content().get(0).getCheckedInAt()).isAfterOrEqualTo(first.content().get(1).getCheckedInAt());
        assertThat(first.content().get(0).getQrCodeHash()).isEqualTo("p5");
        assertThat(last.content()).hasSize(1);
        assertThat(last.content().get(0).getQrCodeHash()).isEqualTo("p1");
        assertThat(onlyShow2.totalElements()).isEqualTo(2);
        assertThat(onlyShow2.content()).extracting(TicketCheckinDTO::getShowtimeId).containsOnly(SHOW_2);
        assertThat(onlyShow2.content()).extracting(TicketCheckinDTO::getTicketTypeName).containsOnly("VIP Night");
    }

    @Test
    void thePageSizeIsKeptBetweenOneAndOneHundredAndAPageBelowZeroIsTheFirst() {
        ticket("q1", general, SHOW_1, TicketStatus.VALID, "o1");
        service.processCheckin(scan("q1"));

        assertThat(service.getCheckinPage(EVENT, null, -3, 0).size()).isEqualTo(1);
        assertThat(service.getCheckinPage(EVENT, null, -3, 0).page()).isZero();
        assertThat(service.getCheckinPage(EVENT, null, 0, 100000).size()).isEqualTo(100);
    }

    @Test
    void theOldListStillReturnsEverythingAndHonoursTheShowtimeOfTheTicketType() {
        ticket("l1", general, null, TicketStatus.VALID, "o1");      // showtime comes from the type (showtime-1)
        ticket("l2", vip, SHOW_2, TicketStatus.VALID, "o2");
        service.processCheckin(scan("l1"));
        service.processCheckin(scan("l2"));

        assertThat(service.getCheckinsByEvent(EVENT, null)).hasSize(2);
        assertThat(service.getCheckinsByEvent(EVENT, "")).hasSize(2);
        assertThat(service.getCheckinsByEvent(EVENT, SHOW_1)).extracting(TicketCheckinDTO::getQrCodeHash).containsExactly("l1");
        assertThat(service.getCheckinsByEvent(EVENT, "nope")).isEmpty();
    }
}
