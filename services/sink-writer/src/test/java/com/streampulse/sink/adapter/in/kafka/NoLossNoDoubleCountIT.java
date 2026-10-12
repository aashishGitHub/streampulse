/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.contracts.v1.MetricAggregate;
import com.streampulse.sink.support.Aggregates;
import com.streampulse.sink.support.ItSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * THE proof for "no loss, no double count" (interview M2).
 *
 * <p>The crash window that matters is after the durable write and before the acknowledgement.
 * A test-only {@link PreAckHook} throws there, three times. The container must then redeliver,
 * the second write must change nothing, and the committed offset must still end at exactly N.
 *
 * <p>What it does not prove: a broker or Postgres failing mid-write, or the JVM being killed
 * (the injected failure is an exception, not a crash). The idempotent upsert, which this test
 * exercises through the redelivery, is what makes a real crash safe as well.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"streampulse.sink.retry.initial-interval=100ms", "streampulse.sink.retry.max-interval=500ms"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NoLossNoDoubleCountIT {

    private static final String TOPIC = "telemetry.agg.v1";
    private static final String DLT = "telemetry.agg.v1.dlt";
    private static final int TITLES = 5;
    private static final int WINDOWS = 6;
    private static final String[] REGIONS = {"us-east-1", "eu-west-1"};
    private static final int N = TITLES * WINDOWS * REGIONS.length;
    private static final int INJECTED_FAILURES = 3;

    private static final AtomicInteger FAILURES_LEFT = new AtomicInteger(INJECTED_FAILURES);
    private static final AtomicInteger FAILURES_INJECTED = new AtomicInteger();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class FaultInjection {
        @Bean
        @Primary
        PreAckHook failAfterTheWriteBeforeTheAck() {
            return () -> {
                if (FAILURES_LEFT.getAndDecrement() > 0) {
                    FAILURES_INJECTED.incrementAndGet();
                    throw new IllegalStateException("injected: after the durable write, before the ack");
                }
            };
        }
    }

    @BeforeAll
    static void createTopics() throws Exception {
        // 6 partitions like docker/compose.yml, so three consumer threads each own two.
        ItSupport.createTopics(KAFKA.getBootstrapServers(), Map.of(TOPIC, 6, DLT, 3));
    }

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    private record Expected(double value, long sampleCount, Instant computedAt) {}

    @Test
    @DisplayName(
            "a failure after the durable write and before the ack redelivers; rows, values and offsets are unchanged")
    void failureBetweenWriteAndAckLosesNothingAndDoubleCountsNothing() throws Exception {
        Map<String, Expected> expected = new HashMap<>();
        List<MetricAggregate> produced = new ArrayList<>();
        int index = 0;
        for (int title = 0; title < TITLES; title++) {
            for (String region : REGIONS) {
                for (int w = 0; w < WINDOWS; w++) {
                    Instant start = Aggregates.WINDOW_START.plusSeconds(10L * w);
                    var aggregate = Aggregates.avro("streamflix", "title-" + title, region, start)
                            .setValue(index)
                            .setSampleCount(100L + index)
                            .build();
                    produced.add(aggregate);
                    expected.put(
                            Aggregates.key(aggregate),
                            new Expected(aggregate.getValue(), aggregate.getSampleCount(), aggregate.getComputedAt()));
                    index++;
                }
            }
        }
        assertThat(produced).hasSize(N);

        for (MetricAggregate aggregate : produced) {
            kafka.send(TOPIC, Aggregates.key(aggregate), Aggregates.bytes(aggregate));
        }
        kafka.flush();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(FAILURES_INJECTED).as("the fault was injected").hasValue(INJECTED_FAILURES);
            assertThat(committedOffsets()).as("every record acknowledged").isEqualTo(N);
        });

        assertThat(rowCount())
                .as("one row per aggregate, however often it was delivered")
                .isEqualTo(N);
        assertThat(storedRows()).isEqualTo(expected);

        double recordsWritten = meters.get("streampulse.sink.freshness").timer().count();
        assertThat(recordsWritten)
                .as("records were written more than once, i.e. the failed polls were redelivered")
                .isGreaterThan(N);

        assertThat(ItSupport.readAll(KAFKA.getBootstrapServers(), DLT))
                .as("a healthy record is never dead-lettered because the ack path failed")
                .isEmpty();

        assertRedisHoldsTheData(expected.keySet());
    }

    private long committedOffsets() throws Exception {
        return ItSupport.committedOffsetTotal(KAFKA.getBootstrapServers(), "sink-writer");
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM metric_window", Integer.class);
    }

    private Map<String, Expected> storedRows() {
        Map<String, Expected> rows = new HashMap<>();
        jdbc.query(
                "SELECT tenant_id, title_id, region, metric, window_start, value, sample_count, computed_at FROM metric_window",
                rs -> {
                    String key = rs.getString("tenant_id") + ":" + rs.getString("title_id") + ":"
                            + rs.getString("region") + ":" + rs.getString("metric") + ":"
                            + rs.getObject("window_start", java.time.OffsetDateTime.class)
                                    .toInstant()
                                    .toEpochMilli();
                    rows.put(
                            key,
                            new Expected(
                                    rs.getDouble("value"),
                                    rs.getLong("sample_count"),
                                    rs.getObject("computed_at", java.time.OffsetDateTime.class)
                                            .toInstant()));
                });
        return rows;
    }

    /** The hash holds one entry per series; the stream holds every window (possibly twice after a redelivery). */
    private void assertRedisHoldsTheData(java.util.Set<String> expectedKeys) {
        var latest = redis.opsForHash().entries("sp:latest:streamflix");
        assertThat(latest).hasSize(TITLES * REGIONS.length);
        assertThat(latest.keySet()).contains("title-0:us-east-1:CONCURRENT_VIEWERS");
        assertThat(String.valueOf(latest.get("title-0:us-east-1:CONCURRENT_VIEWERS")))
                .contains("\"windowStart\":", "\"windowEnd\":", "\"value\":", "\"sampleCount\":", "\"computedAt\":");

        var entries = redis.opsForStream().range("sp:agg:live", Range.unbounded());
        assertThat(entries).isNotNull().hasSizeGreaterThanOrEqualTo(N);
        var distinct = new java.util.HashSet<String>();
        for (var entry : entries) {
            Map<Object, Object> f = entry.getValue();
            assertThat(f.keySet())
                    .containsExactlyInAnyOrder(
                            "tenantId",
                            "titleId",
                            "region",
                            "metric",
                            "windowStart",
                            "windowEnd",
                            "value",
                            "sampleCount",
                            "computedAt");
            distinct.add(f.get("tenantId") + ":" + f.get("titleId") + ":" + f.get("region") + ":" + f.get("metric")
                    + ":" + f.get("windowStart"));
        }
        assertThat(distinct).isEqualTo(expectedKeys);
    }
}
