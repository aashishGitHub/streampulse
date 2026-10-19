/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres per JVM, shared by every integration test class, and one Spring context per distinct
 * configuration. Statistics are on so tests can count the SQL statements Hibernate issues.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
public abstract class PostgresIT {

    /** Shared so a CDC test can put Kafka Connect on the same network as the database. */
    protected static final Network NETWORK = Network.newNetwork();

    /** wal_level=logical and a replication slot are what Debezium needs; the other tests ignore them. */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    protected void emptyEveryTable() {
        jdbc.execute("TRUNCATE control_plane.outbox, control_plane.service_node_dependency,"
                + " control_plane.service_node, control_plane.alert_rule_dependency, control_plane.alert_rule,"
                + " control_plane.title, control_plane.app, control_plane.tenant CASCADE");
    }
}
