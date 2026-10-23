package com.haloticket.ticket_service.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.haloticket.ticket_service.config.CheckinWindowProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.TicketType;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.exception.CheckinException;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import com.haloticket.ticket_service.repository.CheckinRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * A database that does not know the newer result values yet (the CHECK constraint was not migrated) must never turn a
 * refused scan into a 500: the app keeps getting its usual answer and only the attempt row is lost, with an ERROR log.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.url=jdbc:h2:mem:checkin_write_failure;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NEVER)
@Import({CheckinService.class, CheckinAttemptRecorder.class, CheckinAttemptWriteFailureTest.Beans.class})
class CheckinAttemptWriteFailureTest {

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

    @Autowired CheckinService service;
    @Autowired CheckInAttemptRepository attempts;
    @Autowired CheckinRepository checkins;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    private TransactionTemplate tx;
    private TicketType type;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // Production as it is after 20261025 only: the four values it knew when it was created.
        for (String name : jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_name = 'CHECKIN_ATTEMPTS' AND constraint_type = 'CHECK'", String.class)) {
            jdbc.execute("ALTER TABLE checkin_attempts DROP CONSTRAINT \"" + name + "\"");
        }
        jdbc.execute("DELETE FROM checkin_attempts");
        jdbc.execute("ALTER TABLE checkin_attempts ALTER COLUMN result SET DATA TYPE VARCHAR(255)");
        jdbc.execute("ALTER TABLE checkin_attempts ADD CONSTRAINT checkin_attempts_result_check "
                + "CHECK (result IN ('SUCCESS', 'FAILED_SIGNATURE', 'FAILED_ALREADY_IN', 'OUTSIDE_CHECKIN_WINDOW'))");
        type = tx.execute(status -> {
            TicketType t = TicketType.builder().eventId(EVENT).name("GA").price(1d).totalQuantity(10).initialQuantity(10).build();
            em.persist(t);
            return t;
        });
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(CheckinService.class)).addAppender(logs);
    }

    @AfterEach
    void clean() {
        ((Logger) LoggerFactory.getLogger(CheckinService.class)).detachAppender(logs);
        tx.executeWithoutResult(status -> {
            em.createQuery("delete from CheckInAttempt").executeUpdate();
            em.createQuery("delete from Checkin").executeUpdate();
            em.createQuery("delete from Ticket").executeUpdate();
            em.createQuery("delete from TicketType").executeUpdate();
        });
    }

    private Ticket ticket(String qr, TicketStatus status) {
        return tx.execute(s -> {
            Ticket t = Ticket.builder().ticketType(type).eventId(EVENT).ownerId("owner").qrCodeHash(qr)
                    .issueKey("issue-" + qr).status(status).build();
            em.persist(t);
            return t;
        });
    }

    private static CheckinRequest scan(String qr, String event) {
        return CheckinRequest.builder().qrCodeHash(qr).eventId(event).staffAssignmentId("staff").deviceInfo("device").build();
    }

    private List<String> errorLines() {
        return logs.list.stream().filter(e -> e.getLevel().toString().equals("ERROR")).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void wrongEventStillAnswersWrongEventWhenTheAttemptCannotBeStored() {
        Ticket t = ticket("qr-1", TicketStatus.VALID);

        assertThatThrownBy(() -> service.processCheckin(scan("qr-1", "another-event")))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_EVENT"));

        assertThat(attempts.count()).isZero();
        assertThat(errorLines()).singleElement().satisfies(line -> {
            assertThat(line).contains("[CHECKIN_ATTEMPT_LOST]").contains("WRONG_EVENT").contains(t.getId());
            assertThat(line).doesNotContain("qr-1");
        });
    }

    @Test
    void unknownCodeStillAnswersTicketNotFound() {
        assertThatThrownBy(() -> service.processCheckin(scan("no-such-code", EVENT)))
                .isInstanceOfSatisfying(CheckinException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_FOUND"));

        assertThat(errorLines()).singleElement().satisfies(line -> assertThat(line).contains("[CHECKIN_ATTEMPT_LOST]").doesNotContain("no-such-code"));
    }

    @Test
    void lockedTicketStillAnswersLockedWithoutAServerError() {
        ticket("qr-locked", TicketStatus.LOCKED_FOR_RESALE);

        TicketCheckinDTO dto = service.processCheckin(scan("qr-locked", EVENT));

        assertThat(dto.getStatus()).isEqualTo("INVALID");
        assertThat(dto.getCode()).isEqualTo("TICKET_LOCKED");
        assertThat(errorLines()).singleElement().satisfies(line -> assertThat(line).contains("[CHECKIN_ATTEMPT_LOST]").contains("TICKET_LOCKED"));
    }

    @Test
    void aSuccessfulScanIsNotAffectedByTheOutdatedConstraint() {
        ticket("qr-ok", TicketStatus.VALID);

        TicketCheckinDTO dto = service.processCheckin(scan("qr-ok", EVENT));

        assertThat(dto.getCode()).isEqualTo("SUCCESS");
        assertThat(checkins.count()).isEqualTo(1);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(errorLines()).isEmpty();
    }

    @Test
    void valuesTheConstraintAlreadyKnowsAreStoredNormally() {
        ticket("qr-dup", TicketStatus.VALID);
        service.processCheckin(scan("qr-dup", EVENT));

        assertThat(service.processCheckin(scan("qr-dup", EVENT)).getCode()).isEqualTo("ALREADY_USED");

        assertThat(attempts.count()).isEqualTo(2);
        assertThat(errorLines()).isEmpty();
    }
}
