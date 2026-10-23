package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.config.CheckinWindowProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.entity.*;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.enums.CheckInStatus;
import com.haloticket.ticket_service.enums.SeatLayoutStatus;
import com.haloticket.ticket_service.enums.SeatStatus;
import com.haloticket.ticket_service.enums.SeatType;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.exception.CheckinException;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Check-in through CheckinService against a real JPA stack (in-memory database, real transactions, real lazy
 * proxies and the real bulk UPDATE that clears the persistence context). The older tests mock the repositories, so
 * entities are plain objects there and nothing can be lazy or detached.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
@Transactional(propagation = Propagation.NEVER)
@Import({CheckinService.class, CheckinAttemptRecorder.class, CheckinServiceJpaTest.Beans.class})
class CheckinServiceJpaTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    @TestConfiguration
    static class Beans {
        @Bean
        CheckinWindowProperties checkinWindowProperties() {
            return new CheckinWindowProperties();
        }

        @Bean
        EventScheduleService eventScheduleService() {
            return mock(EventScheduleService.class);
        }
    }

    private static final String EVENT = "event-1";
    private static final String SHOWTIME = "showtime-1";

    @Autowired CheckinService service;
    @Autowired CheckinWindowProperties window;
    @Autowired EventScheduleService schedule;
    @Autowired TicketRepository tickets;
    @Autowired CheckinRepository checkins;
    @Autowired CheckInAttemptRepository attempts;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    private TransactionTemplate tx;
    private TicketType type;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        window.setEnabled(false);
        window.setFailOpen(true);
        reset(schedule);
        type = inTx(() -> {
            TicketType t = TicketType.builder().eventId(EVENT).name("VIP Standing").price(500000d)
                    .totalQuantity(100).initialQuantity(100).build();
            em.persist(t);
            return t;
        });
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

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private Ticket gaTicket(String qr, TicketStatus status) {
        return inTx(() -> {
            Ticket t = Ticket.builder().ticketType(type).eventId(EVENT).showtimeId(SHOWTIME).ownerId("owner-1")
                    .qrCodeHash(qr).issueKey("issue-" + qr).status(status).build();
            em.persist(t);
            return t;
        });
    }

    private Ticket seatedTicket(String qr) {
        return inTx(() -> {
            SeatLayout layout = SeatLayout.builder().eventId(EVENT).status(SeatLayoutStatus.PUBLISHED).build();
            em.persist(layout);
            SeatZone zone = SeatZone.builder().seatLayout(layout).name("VIP").build();
            em.persist(zone);
            SeatRow row = SeatRow.builder().seatZone(zone).label("B").build();
            em.persist(row);
            Seat seat = Seat.builder().seatRow(row).seatNumber("7").seatType(SeatType.REGULAR).seatStatus(SeatStatus.SOLD).build();
            em.persist(seat);
            Ticket t = Ticket.builder().ticketType(type).seat(seat).eventId(EVENT).showtimeId(SHOWTIME).ownerId("owner-1")
                    .qrCodeHash(qr).issueKey("issue-" + qr).status(TicketStatus.VALID).build();
            em.persist(t);
            return t;
        });
    }

    private static CheckinRequest scan(String qr) {
        return CheckinRequest.builder().qrCodeHash(qr).eventId(EVENT).showtimeId(SHOWTIME)
                .staffAssignmentId("staff-1").deviceInfo("android").build();
    }

    private TicketStatus statusOf(Ticket t) {
        return inTx(() -> em.find(Ticket.class, t.getId()).getStatus());
    }

    private long attemptsWith(CheckInAttemptResult result) {
        return attempts.findAll().stream().filter(a -> a.getResult() == result).count();
    }

    // --- winning scan -----------------------------------------------------------------

    @Test
    void validGeneralAdmissionScanSucceedsAndTheResponseCarriesTheTicketTypeName() {
        Ticket t = gaTicket("qr-ga", TicketStatus.VALID);

        TicketCheckinDTO dto = service.processCheckin(scan("qr-ga"));

        assertThat(dto.getStatus()).isEqualTo("VALID");
        assertThat(dto.getCode()).isEqualTo("SUCCESS");
        assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing");
        assertThat(dto.getSeatName()).startsWith("Số thứ tự vé: ");
        assertThat(dto.getTicketId()).isEqualTo(t.getId());
        assertThat(dto.getCheckinId()).isNotBlank();
        assertThat(dto.getCheckedInAt()).isNotNull();
        assertThat(dto.getShowtimeId()).isEqualTo(SHOWTIME);
        assertThat(dto.getOwnerId()).isEqualTo("owner-1");
        assertThat(statusOf(t)).isEqualTo(TicketStatus.USED);
        assertThat(checkins.findAll()).hasSize(1);
        assertThat(attemptsWith(CheckInAttemptResult.SUCCESS)).isEqualTo(1);
    }

    @Test
    void validSeatedScanSucceedsAndTheResponseCarriesTheSeatName() {
        seatedTicket("qr-seat");

        TicketCheckinDTO dto = service.processCheckin(scan("qr-seat"));

        assertThat(dto.getCode()).isEqualTo("SUCCESS");
        assertThat(dto.getSeatName()).isEqualTo("Khu VIP - Hàng B - Ghế 7");
        assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing");
        assertThat(checkins.findAll()).hasSize(1);
    }

    @Test
    void scanWithoutAShowtimeAlsoSucceeds() {
        gaTicket("qr-noshow", TicketStatus.VALID);
        CheckinRequest request = CheckinRequest.builder().qrCodeHash("qr-noshow").eventId(EVENT).build();

        TicketCheckinDTO dto = service.processCheckin(request);

        assertThat(dto.getCode()).isEqualTo("SUCCESS");
        assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing");
    }

    // --- second scan ------------------------------------------------------------------

    @Test
    void secondScanIsDuplicatedWithTheSameDetailsAndWritesNoSecondCheckin() {
        gaTicket("qr-dup", TicketStatus.VALID);
        service.processCheckin(scan("qr-dup"));

        TicketCheckinDTO second = service.processCheckin(scan("qr-dup"));

        assertThat(second.getStatus()).isEqualTo("DUPLICATED");
        assertThat(second.getCode()).isEqualTo("ALREADY_USED");
        assertThat(second.getTicketTypeName()).isEqualTo("VIP Standing");
        assertThat(second.getCheckinId()).isNotBlank();
        assertThat(checkins.findAll()).hasSize(1);
        assertThat(attemptsWith(CheckInAttemptResult.FAILED_ALREADY_IN)).isEqualTo(1);
    }

    @Test
    void validTicketThatAlreadyHasACheckinRowIsDuplicatedNotAServerError() {
        Ticket t = gaTicket("qr-inconsistent", TicketStatus.VALID);
        inTx(() -> {
            em.persist(Checkin.builder().ticket(em.getReference(Ticket.class, t.getId())).eventId(EVENT)
                    .status(CheckInStatus.VALID).checkedInAt(LocalDateTime.now().minusMinutes(5)).build());
            return null;
        });

        TicketCheckinDTO dto = service.processCheckin(scan("qr-inconsistent"));

        assertThat(dto.getStatus()).isEqualTo("DUPLICATED");
        assertThat(dto.getCode()).isEqualTo("ALREADY_USED");
        assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing");
        assertThat(checkins.findAll()).hasSize(1);
    }

    // --- locked tickets ---------------------------------------------------------------

    @Test
    void ticketLockedForResaleIsRefusedWithoutACheckinRowAndScansAfterUnlock() {
        Ticket t = gaTicket("qr-lock", TicketStatus.LOCKED_FOR_RESALE);

        TicketCheckinDTO locked = service.processCheckin(scan("qr-lock"));

        assertThat(locked.getStatus()).isEqualTo("INVALID");
        assertThat(locked.getCode()).isEqualTo("TICKET_LOCKED");
        assertThat(locked.getTicketTypeName()).isEqualTo("VIP Standing");
        assertThat(checkins.findAll()).isEmpty();
        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult).containsExactly(CheckInAttemptResult.TICKET_LOCKED);
        assertThat(statusOf(t)).isEqualTo(TicketStatus.LOCKED_FOR_RESALE);

        tx.executeWithoutResult(s -> em.find(Ticket.class, t.getId()).setStatus(TicketStatus.VALID));
        TicketCheckinDTO after = service.processCheckin(scan("qr-lock"));

        assertThat(after.getCode()).isEqualTo("SUCCESS");
        assertThat(checkins.findAll()).hasSize(1);
    }

    @Test
    void ticketLockedForTransferIsRefused() {
        gaTicket("qr-transfer", TicketStatus.LOCKED_FOR_TRANSFER);
        assertThat(service.processCheckin(scan("qr-transfer")).getCode()).isEqualTo("TICKET_LOCKED");
        assertThat(checkins.findAll()).isEmpty();
    }

    // --- business errors ------------------------------------------------------------------

    @Test
    void wrongEventWrongShowtimeAndUnknownTicketAreBusinessErrorsAndNothingIsCheckedIn() {
        Ticket t = gaTicket("qr-err", TicketStatus.VALID);

        CheckinRequest otherEvent = CheckinRequest.builder().qrCodeHash("qr-err").eventId("other").showtimeId(SHOWTIME).build();
        assertThatThrownBy(() -> service.processCheckin(otherEvent))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_EVENT"));

        CheckinRequest otherShowtime = CheckinRequest.builder().qrCodeHash("qr-err").eventId(EVENT).showtimeId("showtime-9").build();
        assertThatThrownBy(() -> service.processCheckin(otherShowtime))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_SHOWTIME"));

        assertThatThrownBy(() -> service.processCheckin(scan("does-not-exist")))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_FOUND"));

        // every refused scan leaves exactly one attempt row, written in its own transaction so the exception cannot undo it
        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult)
                .containsExactlyInAnyOrder(CheckInAttemptResult.WRONG_EVENT, CheckInAttemptResult.WRONG_SHOWTIME,
                        CheckInAttemptResult.TICKET_NOT_FOUND);
        CheckInAttempt unknown = attempts.findAll().stream()
                .filter(a -> a.getResult() == CheckInAttemptResult.TICKET_NOT_FOUND).findFirst().orElseThrow();
        assertThat(unknown.getTicket()).as("a code that matches no ticket is stored without a ticket").isNull();
        assertThat(unknown.getFailureReason()).doesNotContain("does-not-exist");
        assertThat(attempts.findAll().stream().filter(a -> a.getResult() != CheckInAttemptResult.TICKET_NOT_FOUND))
                .allSatisfy(a -> assertThat(a.getTicket().getId()).isEqualTo(t.getId()));
        assertThat(checkins.findAll()).isEmpty();
        assertThat(statusOf(t)).isEqualTo(TicketStatus.VALID);
    }

    // --- check-in window ---------------------------------------------------------------------

    @Test
    void outsideTheWindowIsRefusedAndInsideItSucceeds() {
        window.setEnabled(true);
        Ticket t = gaTicket("qr-window", TicketStatus.VALID);
        when(schedule.getEventSchedule(EVENT)).thenReturn(new EventServiceClient.EventDetailsResponse(
                EVENT, LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(3).plusHours(4), List.of()));

        assertThatThrownBy(() -> service.processCheckin(scan("qr-window")))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("OUTSIDE_CHECKIN_WINDOW"));
        assertThat(statusOf(t)).isEqualTo(TicketStatus.VALID);
        assertThat(checkins.findAll()).isEmpty();
        assertThat(attemptsWith(CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW)).isEqualTo(1);

        when(schedule.getEventSchedule(EVENT)).thenReturn(new EventServiceClient.EventDetailsResponse(
                EVENT, LocalDateTime.now().minusMinutes(30), LocalDateTime.now().plusHours(3), List.of()));
        assertThat(service.processCheckin(scan("qr-window")).getCode()).isEqualTo("SUCCESS");
    }

    @Test
    void windowThatCannotBeVerifiedAndFailsClosedIsRecordedAsUnverifiedButAnsweredAsOutsideTheWindow() {
        window.setEnabled(true);
        window.setFailOpen(false);
        Ticket t = gaTicket("qr-unverified", TicketStatus.VALID);
        when(schedule.getEventSchedule(EVENT)).thenThrow(new IllegalStateException("event-service down"));

        assertThatThrownBy(() -> service.processCheckin(scan("qr-unverified")))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("OUTSIDE_CHECKIN_WINDOW"));

        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult).containsExactly(CheckInAttemptResult.WINDOW_UNVERIFIED);
        assertThat(statusOf(t)).isEqualTo(TicketStatus.VALID);
    }

    @Test
    void windowThatCannotBeVerifiedButFailsOpenLetsTheScanThrough() {
        window.setEnabled(true);
        window.setFailOpen(true);
        gaTicket("qr-open", TicketStatus.VALID);
        when(schedule.getEventSchedule(EVENT)).thenThrow(new IllegalStateException("event-service down"));

        assertThat(service.processCheckin(scan("qr-open")).getCode()).isEqualTo("SUCCESS");
        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult).containsExactly(CheckInAttemptResult.SUCCESS);
    }

    @Test
    void everyOutcomeLeavesExactlyOneAttemptRowWithItsStaffAndDevice() {
        gaTicket("qr-one-1", TicketStatus.VALID);
        service.processCheckin(scan("qr-one-1"));                       // SUCCESS
        service.processCheckin(scan("qr-one-1"));                       // FAILED_ALREADY_IN
        gaTicket("qr-one-2", TicketStatus.LOCKED_FOR_TRANSFER);
        service.processCheckin(scan("qr-one-2"));                       // TICKET_LOCKED
        assertThatThrownBy(() -> service.processCheckin(scan("missing"))).isInstanceOf(CheckinException.class); // TICKET_NOT_FOUND

        assertThat(attempts.findAll()).extracting(CheckInAttempt::getResult).containsExactlyInAnyOrder(
                CheckInAttemptResult.SUCCESS, CheckInAttemptResult.FAILED_ALREADY_IN,
                CheckInAttemptResult.TICKET_LOCKED, CheckInAttemptResult.TICKET_NOT_FOUND);
        assertThat(attempts.findAll()).allSatisfy(a -> {
            assertThat(a.getStaffAssignmentId()).isEqualTo("staff-1");
            assertThat(a.getDeviceInfo()).isEqualTo("android");
            assertThat(a.getAttemptedAt()).isNotNull();
        });
    }

    // --- concurrency ------------------------------------------------------------------------------

    @Test
    void fiftyConcurrentScansOfOneTicketGiveExactlyOneSuccessAndNoServerError() throws Exception {
        Ticket t = gaTicket("qr-race", TicketStatus.VALID);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TicketCheckinDTO>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.processCheckin(scan("qr-race"));
            }));
        }
        ready.await();
        go.countDown();
        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (Future<TicketCheckinDTO> future : futures) {
            try {
                TicketCheckinDTO dto = future.get(60, TimeUnit.SECONDS);
                codes.add(dto.getCode());
                assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing");
            } catch (Exception e) {
                failures.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        pool.shutdown();

        assertThat(failures).as("no request may fail with a server error").isEmpty();
        assertThat(codes).filteredOn("SUCCESS"::equals).hasSize(1);
        assertThat(codes).filteredOn("ALREADY_USED"::equals).hasSize(threads - 1);
        assertThat(checkins.findAll()).hasSize(1);
        assertThat(statusOf(t)).isEqualTo(TicketStatus.USED);
        assertThat(attemptsWith(CheckInAttemptResult.SUCCESS)).isEqualTo(1);
        assertThat(attemptsWith(CheckInAttemptResult.FAILED_ALREADY_IN)).isEqualTo(threads - 1);
    }

    // --- the staff's list of check-ins ------------------------------------------------------------------

    @Test
    void checkinListShowsTicketTypeAndSeatNames() {
        gaTicket("qr-list-1", TicketStatus.VALID);
        seatedTicket("qr-list-2");
        service.processCheckin(scan("qr-list-1"));
        service.processCheckin(scan("qr-list-2"));

        List<TicketCheckinDTO> list = service.getCheckinsByEvent(EVENT, SHOWTIME);

        assertThat(list).hasSize(2).allSatisfy(dto -> assertThat(dto.getTicketTypeName()).isEqualTo("VIP Standing"));
        assertThat(list).extracting(TicketCheckinDTO::getSeatName).anyMatch(name -> name.contains("Ghế 7"));
    }
}
