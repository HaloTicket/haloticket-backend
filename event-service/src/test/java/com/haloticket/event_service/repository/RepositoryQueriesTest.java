package com.haloticket.event_service.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hibernate parses every @Query when the repositories start, so an invalid one fails here without a Postgres. The
 * full application context test needs Keycloak and a database, which is why this narrower one exists.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class RepositoryQueriesTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.haloticket.event_service.entity")
    @EnableJpaRepositories("com.haloticket.event_service.repository")
    static class TestApp {}

    @Autowired EventRepository events;
    @Autowired VoucherRepository vouchers;

    @Test
    void theEnumIsPassedAsAParameterNotWrittenInTheQuery() {
        assertThat(events.findMyEvents("nobody")).isEmpty();
    }

    @Test
    void theVoucherRowLockQueryIsValid() {
        assertThat(vouchers.findByIdForUpdate("missing")).isEmpty();
    }
}
