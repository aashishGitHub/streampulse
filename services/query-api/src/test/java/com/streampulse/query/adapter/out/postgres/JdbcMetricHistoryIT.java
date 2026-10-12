/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.application.HistoryQuery;
import com.streampulse.query.domain.MetricPoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The SQL against a real Postgres, with the table created from the hot-store contract's DDL
 * (specs/04-sink-writer/hot-store-contract.md). Does not prove the sink's upsert; that is the sink's test.
 */
@Testcontainers
class JdbcMetricHistoryIT {

    private static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createTable() {
        var dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS metric_window (
                    tenant_id     text             NOT NULL,
                    title_id      text             NOT NULL DEFAULT '',
                    region        text             NOT NULL,
                    metric        text             NOT NULL,
                    window_start  timestamptz      NOT NULL,
                    window_end    timestamptz      NOT NULL,
                    value         double precision NOT NULL,
                    sample_count  bigint           NOT NULL,
                    computed_at   timestamptz      NOT NULL,
                    PRIMARY KEY (tenant_id, title_id, region, metric, window_start)
                )""");
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM metric_window");
        insert("t1", "title-1", "us-east-1", "ERROR_RATE", 3, 0.03);
        insert("t1", "title-1", "us-east-1", "ERROR_RATE", 1, 0.01);
        insert("t1", "title-1", "us-east-1", "ERROR_RATE", 2, 0.02);
        insert("t1", "title-1", "eu-west-1", "ERROR_RATE", 2, 0.20);
        insert("t1", "title-1", "us-east-1", "CONCURRENT_VIEWERS", 2, 900);
        insert("t1", "title-2", "us-east-1", "ERROR_RATE", 2, 0.99);
        insert("t2", "title-1", "us-east-1", "ERROR_RATE", 2, 0.77);
    }

    private static void insert(String tenant, String title, String region, String metric, int slot, double value) {
        Instant start = T0.plusSeconds(10L * slot);
        jdbc.update(
                "INSERT INTO metric_window VALUES (?,?,?,?,?,?,?,?,?)",
                tenant,
                title,
                region,
                metric,
                Timestamp.from(start),
                Timestamp.from(start.plusSeconds(10)),
                value,
                40L,
                Timestamp.from(start.plusSeconds(16)));
    }

    private static HistoryQuery query(String metric, String title, String region, int fromSlot, int toSlot) {
        return new HistoryQuery(
                "t1", metric, title, region, T0.plusSeconds(10L * fromSlot), T0.plusSeconds(10L * toSlot));
    }

    @Test
    @DisplayName("windows come back oldest first, filtered by tenant, metric and title")
    void oldestFirstAndScoped() {
        List<MetricPoint> points =
                new JdbcMetricHistory(jdbc, 1000).range(query("ERROR_RATE", "title-1", "us-east-1", 0, 10));

        assertThat(points).extracting(MetricPoint::value).containsExactly(0.01, 0.02, 0.03);
        assertThat(points).allSatisfy(point -> {
            assertThat(point.titleId()).isEqualTo("title-1");
            assertThat(point.region()).isEqualTo("us-east-1");
            assertThat(point.sampleCount()).isEqualTo(40);
        });
    }

    @Test
    @DisplayName("the range is half-open: from is included, to is not")
    void rangeIsHalfOpen() {
        List<MetricPoint> points =
                new JdbcMetricHistory(jdbc, 1000).range(query("ERROR_RATE", "title-1", "us-east-1", 2, 3));

        assertThat(points).extracting(MetricPoint::value).containsExactly(0.02);
    }

    @Test
    @DisplayName("without a region or metric the filter is open")
    void optionalFiltersAreOpen() {
        List<MetricPoint> points = new JdbcMetricHistory(jdbc, 1000).range(query(null, "title-1", null, 2, 3));

        assertThat(points)
                .extracting(MetricPoint::metric)
                .containsExactlyInAnyOrder("ERROR_RATE", "ERROR_RATE", "CONCURRENT_VIEWERS");
        assertThat(points).extracting(MetricPoint::region).contains("us-east-1", "eu-west-1");
    }

    @Test
    @DisplayName("the row cap bounds the answer")
    void rowCapBoundsTheAnswer() {
        List<MetricPoint> points =
                new JdbcMetricHistory(jdbc, 2).range(query("ERROR_RATE", "title-1", "us-east-1", 0, 10));

        assertThat(points).extracting(MetricPoint::value).containsExactly(0.01, 0.02);
    }

    @Test
    @DisplayName("another tenant's rows never leak in")
    void tenantIsolation() {
        var other = new HistoryQuery("t2", "ERROR_RATE", null, null, T0, T0.plusSeconds(100));

        assertThat(new JdbcMetricHistory(jdbc, 1000).range(other))
                .extracting(MetricPoint::value)
                .containsExactly(0.77);
    }
}
