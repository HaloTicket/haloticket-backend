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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production table was created when CheckInAttemptResult had three values, and ddl-auto: update never touches a
 * CHECK constraint afterwards. This test rebuilds that old constraint in an in-memory database, shows the database
 * rejecting the newer value, and then applies the real migration file.
 *
 * Limits: the database here is H2 in PostgreSQL mode, not PostgreSQL. It proves the constraint behaviour and that the
 * statements in the migration file are valid and repeatable, not PostgreSQL's own checks (BEGIN/COMMIT are skipped
 * and the pg_constraint verification query is not executed). Run the file against a real PostgreSQL as well.
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

    private static final Path MIGRATION = Path.of("..", "haloticket-infrastructure", "postgres", "migrations",
            "20261025_checkin_attempts_result_check.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired CheckInAttemptRepository attempts;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void installTheOldProductionConstraint() {
        // Drop whatever the current enum made Hibernate generate and put back what production has.
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

    /** The statements of the migration that run before the "VERIFY" section, without BEGIN/COMMIT. */
    private List<String> migrationStatements() throws IOException {
        String sql = Files.readString(MIGRATION);
        String executable = sql.substring(0, sql.indexOf("-- VERIFY"));
        List<String> statements = new ArrayList<>();
        for (String raw : executable.split(";")) {
            String statement = raw.lines().filter(line -> !line.strip().startsWith("--")).reduce("", (a, b) -> a + " " + b).strip();
            if (!statement.isEmpty() && !statement.equalsIgnoreCase("BEGIN") && !statement.equalsIgnoreCase("COMMIT")) {
                statements.add(statement);
            }
        }
        return statements;
    }

    @Test
    void theOldConstraintAcceptsOnlyTheThreeOriginalValuesAndRejectsTheNewOne() {
        save(CheckInAttemptResult.SUCCESS);
        save(CheckInAttemptResult.FAILED_SIGNATURE);
        save(CheckInAttemptResult.FAILED_ALREADY_IN);

        assertThatThrownBy(() -> save(CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("CHECKIN_ATTEMPTS_RESULT_CHECK");
    }

    @Test
    void afterTheMigrationEveryEnumValueIsAcceptedAndTheMigrationCanRunAgain() throws IOException {
        List<String> statements = migrationStatements();
        assertThat(statements).hasSize(2);

        statements.forEach(jdbc::execute);
        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            save(value);
        }
        assertThat(attempts.count()).isEqualTo(CheckInAttemptResult.values().length);

        statements.forEach(jdbc::execute); // second run: nothing breaks
        save(CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW);
    }

    @Test
    void theMigrationListsEveryValueOfTheEnum() throws IOException {
        String sql = Files.readString(MIGRATION);
        for (CheckInAttemptResult value : CheckInAttemptResult.values()) {
            assertThat(sql).as("migration must list " + value).contains("'" + value.name() + "'");
        }
    }
}
