/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.sink.domain.MetricWindow;
import com.streampulse.sink.support.Aggregates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The idempotent upsert against a real Postgres: it is the SQL, not the Java, that makes replays
 * safe, so a mock would prove nothing. Uses the shipped schema.sql, not a copy.
 */
@Testcontainers
class MetricWindowUpsertIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static JdbcTemplate jdbc;
    private static JdbcMetricWindowStore store;

    @BeforeAll
    static void connect() {
        var dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        // The container's log says "ready" slightly before the mapped port reliably accepts the first
        // connection; one failed first connect made this IT flaky under load.
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        });
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        store = new JdbcMetricWindowStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE metric_window");
    }

    private static MetricWindow window(double value, long computedAtOffsetSeconds) {
        var base = Aggregates.window("streamflix", "title-1", "us-east-1", Aggregates.WINDOW_START, value);
        return new MetricWindow(
                base.tenantId(),
                base.titleId(),
                base.region(),
                base.metric(),
                base.windowStart(),
                base.windowEnd(),
                value,
                base.sampleCount(),
                base.computedAt().plusSeconds(computedAtOffsetSeconds));
    }

    private double storedValue() {
        return jdbc.queryForObject("SELECT value FROM metric_window", Double.class);
    }

    @Test
    @DisplayName("the same record twice leaves one row")
    void sameRecordTwiceGivesOneRow() {
        store.upsertAll(List.of(window(1.0, 0)));
        store.upsertAll(List.of(window(1.0, 0)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM metric_window", Integer.class))
                .isEqualTo(1);
        assertThat(storedValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an older computation does not overwrite a newer one")
    void olderComputedAtDoesNotOverwriteNewer() {
        store.upsertAll(List.of(window(2.0, 10)));
        store.upsertAll(List.of(window(1.0, 0)));

        assertThat(storedValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a newer computation replaces an older one")
    void newerComputedAtOverwritesOlder() {
        store.upsertAll(List.of(window(1.0, 0)));
        store.upsertAll(List.of(window(2.0, 10)));

        assertThat(storedValue()).isEqualTo(2.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metric_window", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an older and a newer computation in the same batch, in either order, keep the newer")
    void sameKeyTwiceInOneBatchKeepsTheNewer() {
        store.upsertAll(List.of(window(2.0, 10), window(1.0, 0)));
        assertThat(storedValue()).isEqualTo(2.0);

        jdbc.update("TRUNCATE metric_window");
        store.upsertAll(List.of(window(1.0, 0), window(2.0, 10)));
        assertThat(storedValue()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("an absent title is stored as the empty string, and times round-trip at millisecond precision")
    void emptyTitleAndInstantsRoundTrip() {
        Instant start = Instant.parse("2026-01-15T12:00:07.123Z");
        store.upsertAll(List.of(new MetricWindow(
                "t", null, "r", "ERROR_RATE", start, start.plusSeconds(10), 0.25, 4, start.plusSeconds(15))));

        var row = jdbc.queryForMap("SELECT title_id, window_start, window_end FROM metric_window");

        assertThat(row.get("title_id")).isEqualTo("");
        assertThat(((java.sql.Timestamp) row.get("window_start")).toInstant()).isEqualTo(start);
    }

    @Test
    @DisplayName("schema.sql can be applied twice")
    void schemaIsIdempotent() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
    }
}
