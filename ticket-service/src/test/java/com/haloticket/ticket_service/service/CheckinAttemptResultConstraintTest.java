package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production's checkin_attempts.result CHECK constraint was created by Hibernate with the values the enum had then, and
 * ddl-auto: update never changes it. This test rebuilds the older constraint in an in-memory database, shows the
 * database rejecting newer values, and applies the real migration files in order.
 *
 * Limits: the database here is H2 in PostgreSQL mode, not PostgreSQL. It proves the constraint behaviour and that the
 * statements in the migration files are valid and repeatable, not PostgreSQL's own checks (BEGIN/COMMIT are skipped and
 * the pg_constraint verification queries are not executed). The files were also run against a real PostgreSQL.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "spring.datasource.url=jdbc:h2:mem:checkin_constraint;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NEVER)
class CheckinAttemptResultConstraintTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.ticket_service.entity")
    @EnableJpaRepositories("com.haloticket.ticket_service.repository")
    static class TestApp {}

    private static final Path MIGRATIONS = Path.of("..", "haloticket-infrastructure", "postgres", "migrations");
    private static final Path FIRST = MIGRATIONS.resolve("20261025_checkin_attempts_result_check.sql");
    private static final Path SECOND = MIGRATIONS.resolve("20261026_checkin_attempts_result_values.sql");

    private static final Set<CheckInAttemptResult> THREE_ORIGINAL = EnumSet.of(CheckInAttemptResult.SUCCESS,
            CheckInAttemptResult.FAILED_SIGNATURE, CheckInAttemptResult.FAILED_ALREADY_IN);
    private static final Set<CheckInAttemptResult> NEWER = EnumSet.of(CheckInAttemptResult.TICKET_NOT_FOUND,
            CheckInAttemptResult.WRONG_EVENT, CheckInAttemptResult.WRONG_SHOWTIME, CheckInAttemptResult.TICKET_LOCKED,
            CheckInAttemptResult.WINDOW_UNVERIFIED);

    @Autowired JdbcTemplate jdbc;
    @Autowired CheckInAttemptRepository attempts;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void installTheOriginalProductionConstraint() {
        // Drop whatever the current enum made Hibernate generate and put back what production was created with.
        List<String> checks = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_name = 'CHECKIN_ATTEMPTS' AND constraint_type = 'CHECK'", String.class);
        for (String name : checks) {
            jdbc.execute("ALTER TABLE checkin_attempts DROP CONSTRAINT \"" + name + "\"");
        }
        jdbc.execute("DELETE FROM checkin_attempts");
        // PostgreSQL stores the enum as varchar(255); H2 would use its own ENUM type.
        jdbc.execute("ALTER TABLE checkin_attempts ALTER COLUMN result SET DATA TYPE VARCHAR(255)");
        jdbc.execute("ALTER TABLE checkin_attempts ADD CONSTRAINT checkin_attempts_result_check "
                + "CHECK (result IN ('SUCCESS', 'FAILED_SIGNATURE', 'FAILED_ALREADY_IN'))");
    }

    private void save(CheckInAttemptResult result) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            attempts.save(CheckInAttempt.builder().staffAssignmentId("staff").deviceInfo("device").result(result)
                    .attemptedAt(LocalDateTime.now()).build());
            em.flush();
        });
    }

    /** The statements of a migration that run before its "VERIFY" section, without BEGIN/COMMIT. */
    private List<String> statementsOf(Path migration) throws IOException {
        String sql = Files.readString(migration);
        String executable = sql.substring(0, sql.indexOf("-- VERIFY"));
        // comments first: a ';' inside a comment must not split a statement
        String withoutComments = executable.lines().filter(line -> !line.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b);
        List<String> statements = new ArrayList<>();
        for (String raw : withoutComments.split(";")) {
            String statement = raw.strip().replaceAll("\\s+", " ");
            if (!statement.isEmpty() && !statement.equalsIgnoreCase("BEGIN") && !statement.equalsIgnoreCase("COMMIT")) {
                statements.add(statement);
            }
        }
        return statements;
    }

    private void apply(Path migration) throws IOException {
        List<String> statements = statementsOf(migration);
        assertThat(statements).hasSize(2);
        statements.forEach(jdbc::execute);
    }

    @Test
    void theOriginalConstraintAcceptsOnlyTheThreeOriginalValues() {
        THREE_ORIGINAL.forEach(this::save);

        for (CheckInAttemptResult value : EnumSet.complementOf(EnumSet.copyOf(THREE_ORIGINAL))) {
            assertThatThrownBy(() -> save(value)).as(value.name())
                    .isInstanceOf(PersistenceException.class)
                    .hasStackTraceContaining("CHECKIN_ATTEMPTS_RESULT_CHECK");
        }
    }

    @Test
    void theFirstMigrationAddsOnlyTheWindowValueAndStillRejectsTheNewerOnes() throws IOException {
        apply(FIRST);

        save(CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW);
        for (CheckInAttemptResult value : NEWER) {
            assertThatThrownBy(() -> save(value)).as(value.name()).isInstanceOf(PersistenceException.class);
        }
    }

    @Test
    void afterBothMigrationsEveryEnumValueIsAcceptedAndTheSecondOneCanRunAgain() throws IOException {
        apply(FIRST);
        apply(SECOND);

        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            save(value);
        }
        assertThat(attempts.count()).isEqualTo(CheckInAttemptResult.values().length);

        apply(SECOND);
        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            save(value);
        }
    }

    @Test
    void theSecondMigrationAloneIsEnoughOnAnyEarlierState() throws IOException {
        apply(SECOND);
        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            save(value);
        }
    }

    @Test
    void theLatestMigrationListsEveryValueOfTheEnum() throws IOException {
        String sql = Files.readString(SECOND);
        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            assertThat(sql).as("migration must list " + value).contains("'" + value.name() + "'");
        }
    }
}
