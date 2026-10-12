/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.sink.support.Aggregates;
import com.streampulse.sink.support.ItSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Bytes that are not an aggregate go to the dead-letter topic untouched, healthy records around
 * them are stored, and the poison record does not hold the group back: its offset is committed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PoisonRecordIT {

    private static final String TOPIC = "telemetry.agg.v1";
    private static final String DLT = "telemetry.agg.v1.dlt";

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

    @BeforeAll
    static void createTopics() throws Exception {
        ItSupport.createTopics(KAFKA.getBootstrapServers(), Map.of(TOPIC, 6, DLT, 3));
    }

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("garbage bytes appear on the dead-letter topic with key and reason; healthy records are stored")
    void garbageIsDeadLetteredAndHealthyRecordsAreStored() throws Exception {
        byte[] garbage = "definitely not avro".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 5; i++) {
            send("title-a" + i);
        }
        kafka.send(TOPIC, "poison-1", garbage);
        kafka.send(TOPIC, "poison-2", Arrays.copyOf(garbage, 5));
        for (int i = 0; i < 5; i++) {
            send("title-b" + i);
        }
        kafka.flush();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM metric_window", Integer.class))
                    .isEqualTo(10);
            assertThat(ItSupport.committedOffsetTotal(KAFKA.getBootstrapServers(), "sink-writer"))
                    .as("the poison records are acknowledged too, so they do not block the group")
                    .isEqualTo(12);
        });

        var dead = ItSupport.readAll(KAFKA.getBootstrapServers(), DLT);
        assertThat(dead).hasSize(2);
        assertThat(dead).extracting(r -> r.key()).containsExactlyInAnyOrder("poison-1", "poison-2");
        var first = dead.stream()
                .filter(r -> r.key().equals("poison-1"))
                .findFirst()
                .orElseThrow();
        assertThat(first.value()).isEqualTo(garbage);
        assertThat(new String(first.headers().lastHeader("dlt-reason").value(), StandardCharsets.UTF_8))
                .contains("decode");
        assertThat(meters.get("streampulse.sink.dead.letters").counter().count())
                .isEqualTo(2.0);
    }

    private void send(String title) {
        var aggregate = Aggregates.avro("streamflix", title, "us-east-1", Aggregates.WINDOW_START)
                .build();
        kafka.send(TOPIC, Aggregates.key(aggregate), Aggregates.bytes(aggregate));
    }
}
